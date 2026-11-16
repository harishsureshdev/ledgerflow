// LedgerFlow Frontend API Client

export interface UserPersona {
  role: 'ROLE_CUSTOMER' | 'ROLE_OPS_AGENT' | 'ROLE_FINANCE_ADMIN';
  username: string;
  label: string;
  description: string;
}

export const PERSONAS: UserPersona[] = [
  {
    role: 'ROLE_CUSTOMER',
    username: 'cust_alice',
    label: 'Customer (Alice)',
    description: 'Can initiate payments & view only Alice\'s own payments'
  },
  {
    role: 'ROLE_OPS_AGENT',
    username: 'agent_smith',
    label: 'Ops Agent (Smith)',
    description: 'Can view all payments & recon items, but CANNOT approve money adjustments'
  },
  {
    role: 'ROLE_FINANCE_ADMIN',
    username: 'finance_chief',
    label: 'Finance Admin (Chief)',
    description: 'Full authority: Can approve financial adjustments & compensating ledger entries'
  }
];

let currentToken: string | null = null;
let currentPersona: UserPersona = PERSONAS[0];

export async function setPersona(persona: UserPersona): Promise<string> {
  currentPersona = persona;
  try {
    const res = await fetch('/api/v1/auth/demo-token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: persona.username, role: persona.role })
    });
    const data = await res.json();
    currentToken = data.accessToken;
    return data.accessToken;
  } catch (e) {
    console.error('Failed to get demo token', e);
    return '';
  }
}

export function getCurrentPersona(): UserPersona {
  return currentPersona;
}

async function authHeaders(customHeaders: Record<string, string> = {}): Promise<Record<string, string>> {
  if (!currentToken) {
    await setPersona(currentPersona);
  }
  return {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${currentToken}`,
    'X-Merchant-Id': 'merch_demo_store',
    ...customHeaders
  };
}

export interface PaymentRequest {
  customerId: string;
  amount: number; // minor units (cents)
  currency: string;
  paymentToken: string;
}

export interface PaymentResponse {
  id: string;
  merchantId: string;
  customerId: string;
  amount: number;
  currency: string;
  status: string;
  paymentToken: string;
  createdAt: string;
}

export async function createPayment(idempotencyKey: string, request: PaymentRequest): Promise<{ status: number; data: any; durationMs: number }> {
  const start = performance.now();
  const headers = await authHeaders({ 'Idempotency-Key': idempotencyKey });
  const res = await fetch('/api/v1/payments', {
    method: 'POST',
    headers,
    body: JSON.stringify(request)
  });
  const durationMs = Math.round(performance.now() - start);
  const data = await res.json();
  return { status: res.status, data, durationMs };
}

export async function getPayments(): Promise<PaymentResponse[]> {
  try {
    const headers = await authHeaders();
    const res = await fetch('/api/v1/payments', { headers });
    if (!res.ok) return [];
    return await res.json();
  } catch (e) {
    return [];
  }
}

export interface AccountSummary {
  id: string;
  accountNumber: string;
  accountType: string;
  balance: number;
  currency: string;
  allowNegative: boolean;
}

export interface BalanceCheckResult {
  balanced: boolean;
  sumAll: number;
  totalDebits: number;
  totalCredits: number;
  message: string;
  checkedAt: string;
}

export async function getAccounts(): Promise<AccountSummary[]> {
  try {
    const headers = await authHeaders();
    const res = await fetch('/api/v1/ledger/accounts', { headers });
    if (!res.ok) return [];
    return await res.json();
  } catch (e) {
    return [];
  }
}

export async function getBalanceCheck(): Promise<BalanceCheckResult | null> {
  try {
    const headers = await authHeaders();
    const res = await fetch('/api/v1/ledger/balance-check', { headers });
    if (!res.ok) return null;
    return await res.json();
  } catch (e) {
    return null;
  }
}

export interface ReconItem {
  id: string;
  settlementFileId: string;
  mismatchType: 'MISSING_IN_BANK' | 'MISSING_IN_LEDGER' | 'AMOUNT_MISMATCH' | 'DUPLICATE_SETTLEMENT';
  paymentReference: string;
  bankAmount: number | null;
  ledgerAmount: number | null;
  currency: string;
  status: 'OPEN' | 'RESOLVED';
  createdAt: string;
}

export async function getReconItems(): Promise<ReconItem[]> {
  try {
    const headers = await authHeaders();
    const res = await fetch('/api/v1/recon/items', { headers });
    if (!res.ok) return [];
    return await res.json();
  } catch (e) {
    return [];
  }
}

export async function simulateReconciliation(): Promise<ReconItem[]> {
  const headers = await authHeaders();
  const res = await fetch('/api/v1/recon/simulate', { method: 'POST', headers });
  if (!res.ok) throw new Error('Simulation failed: ' + res.status);
  return await res.json();
}

export async function approveAdjustment(reconItemId: string, reason: string): Promise<any> {
  const headers = await authHeaders();
  const res = await fetch(`/api/v1/adjustments/approve?reconItemId=${reconItemId}&reason=${encodeURIComponent(reason)}`, {
    method: 'POST',
    headers
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.detail || err.message || `HTTP ${res.status} Forbidden / Unauthorized`);
  }
  return await res.json();
}

// ---- AI investigator (read-only AI proposes; only FINANCE_ADMIN approval moves money) ----

export interface InvestigationProposal {
  id: string;
  reconItemId: string;
  rootCause: string;
  evidenceIds: string[];
  proposedAction: string;
  recommendedAmount: number | null; // minor units, always computed by code, never by the model
  currency: string;
  rationale: string;
  confidenceScore: number;
  status: 'PENDING_APPROVAL' | 'APPROVED' | string;
  createdAt: string;
  generatedBy: 'AI_MODEL' | 'RULES' | string;
  guardrailNote: string | null;
}

async function errorFrom(res: Response): Promise<Error> {
  const err = await res.json().catch(() => ({}));
  return new Error(err.detail || err.message || `HTTP ${res.status}`);
}

export async function getProposals(): Promise<InvestigationProposal[]> {
  try {
    const res = await fetch('/api/v1/investigator/proposals', { headers: await authHeaders() });
    if (!res.ok) return []; // customers are not allowed to see proposals
    return await res.json();
  } catch (e) {
    return [];
  }
}

export async function investigateItem(reconItemId: string): Promise<InvestigationProposal> {
  const res = await fetch(`/api/v1/investigator/investigate/${reconItemId}`, { method: 'POST', headers: await authHeaders() });
  if (!res.ok) throw await errorFrom(res);
  return await res.json();
}

export async function approveProposal(proposalId: string, notes: string): Promise<InvestigationProposal> {
  const res = await fetch(
    `/api/v1/investigator/approve-proposal?proposalId=${proposalId}&notes=${encodeURIComponent(notes)}`,
    { method: 'POST', headers: await authHeaders() }
  );
  if (!res.ok) throw await errorFrom(res);
  return await res.json();
}

/** Actions that post a ledger entry when approved; the others record a decision only. */
export const MONEY_MOVING_ACTIONS = ['COMPENSATING_CREDIT_SETTLEMENT', 'COMPENSATING_DEBIT_SETTLEMENT'];

export interface ChaosConfig {
  latencyMs: number;
  failureRate: number;
  duplicateRate: number;
}

export async function getChaosConfig(): Promise<ChaosConfig> {
  try {
    const res = await fetch('/api/v1/chaos/config', { headers: await authHeaders() });
    if (!res.ok) return { latencyMs: 0, failureRate: 0, duplicateRate: 0 };
    return await res.json();
  } catch (e) {
    return { latencyMs: 0, failureRate: 0, duplicateRate: 0 };
  }
}

export async function updateChaosConfig(config: ChaosConfig): Promise<ChaosConfig> {
  const res = await fetch('/api/v1/chaos/config', {
    method: 'POST',
    headers: await authHeaders(), // chaos controls are OPS_AGENT / FINANCE_ADMIN only
    body: JSON.stringify(config)
  });
  if (!res.ok) throw new Error(`Chaos update rejected (${res.status}): switch to an Ops or Finance persona`);
  return await res.json();
}
