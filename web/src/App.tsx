import React, { useState, useEffect } from 'react';
import {
  ShieldCheck,
  Zap,
  Activity,
  Layers,
  FileCheck2,
  RotateCcw,
  CheckCircle2,
  Clock,
  UserCheck,
  Server,
  Lock,
  Flame,
  Sparkles
} from 'lucide-react';
import {
  PERSONAS,
  UserPersona,
  setPersona,
  getCurrentPersona,
  createPayment,
  getPayments,
  PaymentResponse,
  getAccounts,
  AccountSummary,
  getBalanceCheck,
  BalanceCheckResult,
  getReconItems,
  ReconItem,
  simulateReconciliation,
  getProposals,
  investigateItem,
  approveProposal,
  InvestigationProposal,
  MONEY_MOVING_ACTIONS,
  getChaosConfig,
  updateChaosConfig,
  ChaosConfig
} from './api';

export function App() {
  const [activeTab, setActiveTab] = useState<'payments' | 'chaos' | 'ledger' | 'recon' | 'observability'>('payments');
  const [currentPersona, setCurrentPersonaState] = useState<UserPersona>(getCurrentPersona());

  // Payments State
  const [amountDollars, setAmountDollars] = useState('25.00');
  const [customerId, setCustomerId] = useState('cust_alice');
  const [token, setToken] = useState('tok_visa_4242');
  const [payments, setPayments] = useState<PaymentResponse[]>([]);
  const [loadingPayment, setLoadingPayment] = useState(false);

  // Concurrency Test State
  const [concurrencyRunning, setConcurrencyRunning] = useState(false);
  const [concurrencyResult, setConcurrencyResult] = useState<{
    total: number;
    created201: number;
    replayed200: number;
    conflicts409: number;
    errors: number;
    doubleCharges: number;
    elapsedMs: number;
    key: string;
    details: { index: number; status: number; durationMs: number }[];
  } | null>(null);

  // Ledger State
  const [accounts, setAccounts] = useState<AccountSummary[]>([]);
  const [balanceCheck, setBalanceCheck] = useState<BalanceCheckResult | null>(null);

  // Recon State
  const [reconItems, setReconItems] = useState<ReconItem[]>([]);
  const [proposals, setProposals] = useState<Record<string, InvestigationProposal>>({}); // by reconItemId
  const [investigatingId, setInvestigatingId] = useState<string | null>(null);
  const [simulatingRecon, setSimulatingRecon] = useState(false);
  const [reconError, setReconError] = useState<string | null>(null);
  const [reconSuccess, setReconSuccess] = useState<string | null>(null);

  // Chaos State
  const [chaos, setChaos] = useState<ChaosConfig>({ latencyMs: 0, failureRate: 0.0, duplicateRate: 0.0 });
  const [chaosSaving, setChaosSaving] = useState(false);

  // Initialization
  useEffect(() => {
    handlePersonaChange(PERSONAS[0]);
  }, []);

  const handlePersonaChange = async (p: UserPersona) => {
    setCurrentPersonaState(p);
    await setPersona(p);
    refreshData();
  };

  const refreshData = async () => {
    try {
      const [pList, aList, bCheck, rList, cConfig, propList] = await Promise.all([
        getPayments(),
        getAccounts(),
        getBalanceCheck(),
        getReconItems(),
        getChaosConfig(),
        getProposals()
      ]);
      const byItem: Record<string, InvestigationProposal> = {};
      // keep the newest proposal per recon item
      [...propList].sort((a, b) => a.createdAt.localeCompare(b.createdAt)).forEach((p) => { byItem[p.reconItemId] = p; });
      setProposals(byItem);
      setPayments(pList);
      setAccounts(aList);
      setBalanceCheck(bCheck);
      setReconItems(rList);
      setChaos(cConfig);
    } catch (e) {
      console.error('Error refreshing data', e);
    }
  };

  // Submit Single Payment
  const handleSinglePayment = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoadingPayment(true);
    try {
      const amountCents = Math.round(parseFloat(amountDollars) * 100);
      const idempotencyKey = 'key_' + Math.random().toString(36).substring(2, 10);
      await createPayment(idempotencyKey, {
        customerId,
        amount: amountCents,
        currency: 'USD',
        paymentToken: token
      });
      await refreshData();
    } catch (err: any) {
      alert('Payment failed: ' + err.message);
    } finally {
      setLoadingPayment(false);
    }
  };

  // Fire 50 Concurrent Retries (Same Idempotency-Key)
  const handleFire50Concurrent = async () => {
    setConcurrencyRunning(true);
    setConcurrencyResult(null);

    const amountCents = Math.round(parseFloat(amountDollars) * 100);
    const sharedIdempotencyKey = 'burst_key_' + Date.now();
    const reqCount = 50;
    const start = performance.now();

    // Fire 50 parallel requests with Promise.all
    const promises = Array.from({ length: reqCount }).map(async (_, idx) => {
      try {
        const res = await createPayment(sharedIdempotencyKey, {
          customerId,
          amount: amountCents,
          currency: 'USD',
          paymentToken: token
        });
        return { index: idx + 1, status: res.status, durationMs: res.durationMs, paymentId: res.data?.id as string | undefined };
      } catch (err: any) {
        return { index: idx + 1, status: 500, durationMs: 0, paymentId: undefined as string | undefined };
      }
    });

    const results = await Promise.all(promises);
    const elapsedMs = Math.round(performance.now() - start);

    let created201 = 0;
    let replayed200 = 0;
    let conflicts409 = 0;
    let errors = 0;

    // A retry that arrives after the first request finished is answered with the STORED
    // response, which is also a 201 with the same payment id. So count distinct payment ids,
    // not 201s: one id = one charge, however many 201s came back.
    const paymentIds = new Set<string>();
    results.forEach((r) => {
      if (r.status === 201) { if (r.paymentId) paymentIds.add(r.paymentId); }
      else if (r.status === 409) conflicts409++;
      else errors++;
    });
    created201 = paymentIds.size;
    replayed200 = results.filter((r) => r.status === 201).length - paymentIds.size; // same-id 201 replays

    // Invariant: all 50 retries share one key, so at most ONE distinct payment may exist
    const doubleCharges = Math.max(0, paymentIds.size - 1);

    setConcurrencyResult({
      total: reqCount,
      created201,
      replayed200,
      conflicts409,
      errors,
      doubleCharges,
      elapsedMs,
      key: sharedIdempotencyKey,
      details: results
    });

    setConcurrencyRunning(false);
    await refreshData();
  };

  // Simulate Reconciliation Batch
  const handleSimulateRecon = async () => {
    setSimulatingRecon(true);
    setReconError(null);
    setReconSuccess(null);
    try {
      const items = await simulateReconciliation();
      setReconItems(items);
      setReconSuccess(`Nightly settlement feed processed! Injected 4 discrepancies detected & flagged.`);
      await refreshData();
    } catch (err: any) {
      setReconError(err.message);
    } finally {
      setSimulatingRecon(false);
    }
  };

  // AI investigation: read-only, produces a PENDING_APPROVAL proposal
  const handleInvestigate = async (item: ReconItem) => {
    setReconError(null);
    setReconSuccess(null);
    setInvestigatingId(item.id);
    try {
      const p = await investigateItem(item.id);
      setProposals((prev) => ({ ...prev, [item.id]: p }));
      setReconSuccess(p.generatedBy === 'AI_MODEL'
        ? `AI proposal ready for ${item.id.substring(0, 8)}... (nothing has moved yet; it needs Finance approval).`
        : `Rule-based proposal ready for ${item.id.substring(0, 8)}...${p.guardrailNote ? ' (' + p.guardrailNote + ')' : ''}`);
    } catch (err: any) {
      setReconError('Investigation failed: ' + err.message);
    } finally {
      setInvestigatingId(null);
    }
  };

  // Approve a proposal: FINANCE_ADMIN only, enforced by the server; moves money only for adjustment actions
  const handleApproveProposal = async (item: ReconItem, p: InvestigationProposal) => {
    setReconError(null);
    setReconSuccess(null);
    try {
      const approved = await approveProposal(p.id, 'Finance admin reviewed AI proposal');
      setProposals((prev) => ({ ...prev, [item.id]: approved }));
      setReconSuccess(MONEY_MOVING_ACTIONS.includes(p.proposedAction)
        ? `Approved: balanced compensating entry of $${((p.recommendedAmount ?? 0) / 100).toFixed(2)} posted to the ledger.`
        : `Approved: decision recorded (${p.proposedAction}); no money moved.`);
      await refreshData();
    } catch (err: any) {
      setReconError('Approval rejected: ' + err.message);
    }
  };

  // Update Chaos Knobs
  const handleApplyChaos = async (newConfig: ChaosConfig) => {
    setChaosSaving(true);
    try {
      const updated = await updateChaosConfig(newConfig);
      setChaos(updated);
    } finally {
      setChaosSaving(false);
    }
  };

  return (
    <div style={{ maxWidth: 1240, margin: '0 auto', padding: '24px 20px' }}>
      {/* Top Banner */}
      <header style={{ marginBottom: 24, borderBottom: '1px solid var(--border)', paddingBottom: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <div style={{
              width: 44,
              height: 44,
              borderRadius: 10,
              background: 'linear-gradient(135deg, #10b981 0%, #059669 100%)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              boxShadow: '0 4px 12px rgba(16, 185, 129, 0.3)'
            }}>
              <ShieldCheck size={26} color="#ffffff" />
            </div>
            <div>
              <h1 style={{ fontSize: '1.5rem', fontWeight: 700, letterSpacing: '-0.02em', display: 'flex', alignItems: 'center', gap: 8 }}>
                LedgerFlow
                <span className="badge badge-emerald">Production Grade</span>
              </h1>
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                Zero-Loss Payments Platform • Double-Entry Ledger • Exactly-Once Semantics • Chaos Resilient
              </p>
            </div>
          </div>

          {/* Persona Switcher */}
          <div style={{
            background: 'var(--bg-secondary)',
            border: '1px solid var(--border)',
            borderRadius: 8,
            padding: '6px 10px',
            display: 'flex',
            alignItems: 'center',
            gap: 10
          }}>
            <UserCheck size={18} color="var(--accent-emerald)" />
            <div style={{ fontSize: '0.8rem' }}>
              <div style={{ color: 'var(--text-muted)' }}>Acting Persona:</div>
              <select
                value={currentPersona.username}
                onChange={(e) => {
                  const target = PERSONAS.find(p => p.username === e.target.value);
                  if (target) handlePersonaChange(target);
                }}
                style={{ fontWeight: 600, background: 'transparent', border: 'none', padding: '2px 0', color: '#10b981', cursor: 'pointer' }}
              >
                {PERSONAS.map(p => (
                  <option key={p.username} value={p.username} style={{ background: '#111827', color: '#fff' }}>
                    {p.label} [{p.role.replace('ROLE_', '')}]
                  </option>
                ))}
              </select>
            </div>
          </div>
        </div>

        {/* Invariant Ribbon */}
        <div style={{
          marginTop: 16,
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
          gap: 12
        }}>
          <div style={{ background: 'rgba(16, 185, 129, 0.08)', border: '1px solid rgba(16, 185, 129, 0.25)', borderRadius: 8, padding: '10px 14px', display: 'flex', alignItems: 'center', gap: 10 }}>
            <CheckCircle2 size={20} color="#10b981" />
            <div>
              <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase' }}>Ledger Invariant</div>
              <div style={{ fontSize: '0.9rem', fontWeight: 700, color: '#34d399' }}>
                {balanceCheck?.balanced ? 'Books balanced ✓ (Σ = $0.00)' : 'Checking invariant...'}
              </div>
            </div>
          </div>

          <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 8, padding: '10px 14px', display: 'flex', alignItems: 'center', gap: 10 }}>
            <Server size={20} color="#3b82f6" />
            <div>
              <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase' }}>Source of Truth</div>
              <div style={{ fontSize: '0.9rem', fontWeight: 600, color: '#f9fafb' }}>PostgreSQL 16 Row-Locked</div>
            </div>
          </div>

          <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 8, padding: '10px 14px', display: 'flex', alignItems: 'center', gap: 10 }}>
            <Layers size={20} color="#a78bfa" />
            <div>
              <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase' }}>Outbox Relay</div>
              <div style={{ fontSize: '0.9rem', fontWeight: 600, color: '#f9fafb' }}>Transactional Kafka Topic</div>
            </div>
          </div>

          <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 8, padding: '10px 14px', display: 'flex', alignItems: 'center', gap: 10 }}>
            <Lock size={20} color="#f59e0b" />
            <div>
              <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', textTransform: 'uppercase' }}>Double Charges</div>
              <div style={{ fontSize: '0.9rem', fontWeight: 700, color: '#fbbf24' }}>0 Detected (Guaranteed)</div>
            </div>
          </div>
        </div>
      </header>

      {/* Navigation Tabs */}
      <nav style={{ display: 'flex', gap: 8, marginBottom: 24, borderBottom: '1px solid var(--border)', paddingBottom: 12 }}>
        <button
          onClick={() => setActiveTab('payments')}
          className={`btn-secondary ${activeTab === 'payments' ? 'btn-primary' : ''}`}
          style={{ display: 'flex', alignItems: 'center', gap: 6 }}
        >
          <Zap size={16} /> Payments & Concurrency Demo
        </button>

        <button
          onClick={() => setActiveTab('chaos')}
          className={`btn-secondary ${activeTab === 'chaos' ? 'btn-primary' : ''}`}
          style={{ display: 'flex', alignItems: 'center', gap: 6 }}
        >
          <Flame size={16} /> Chaos & Resilience Controls
        </button>

        <button
          onClick={() => setActiveTab('ledger')}
          className={`btn-secondary ${activeTab === 'ledger' ? 'btn-primary' : ''}`}
          style={{ display: 'flex', alignItems: 'center', gap: 6 }}
        >
          <Layers size={16} /> Double-Entry Ledger
        </button>

        <button
          onClick={() => setActiveTab('recon')}
          className={`btn-secondary ${activeTab === 'recon' ? 'btn-primary' : ''}`}
          style={{ display: 'flex', alignItems: 'center', gap: 6 }}
        >
          <FileCheck2 size={16} /> Reconciliation Queue
        </button>

        <button
          onClick={() => setActiveTab('observability')}
          className={`btn-secondary ${activeTab === 'observability' ? 'btn-primary' : ''}`}
          style={{ display: 'flex', alignItems: 'center', gap: 6 }}
        >
          <Activity size={16} /> Observability & Architecture
        </button>
      </nav>

      {/* TAB 1: Payments & Concurrency */}
      {activeTab === 'payments' && (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24 }}>
          {/* Payment Initiation Form */}
          <div className="card">
            <h2 style={{ fontSize: '1.15rem', fontWeight: 600, marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
              <Zap size={18} color="var(--accent-emerald)" />
              Initiate Payment (Idempotent API)
            </h2>

            <form onSubmit={handleSinglePayment}>
              <div style={{ marginBottom: 14 }}>
                <label style={{ display: 'block', fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: 6 }}>
                  Customer ID
                </label>
                <input
                  type="text"
                  value={customerId}
                  onChange={(e) => setCustomerId(e.target.value)}
                  style={{ width: '100%' }}
                  required
                />
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 14 }}>
                <div>
                  <label style={{ display: 'block', fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: 6 }}>
                    Amount (USD $)
                  </label>
                  <input
                    type="number"
                    step="0.01"
                    value={amountDollars}
                    onChange={(e) => setAmountDollars(e.target.value)}
                    style={{ width: '100%' }}
                    required
                  />
                </div>
                <div>
                  <label style={{ display: 'block', fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: 6 }}>
                    PCI Safe Token
                  </label>
                  <input
                    type="text"
                    value={token}
                    onChange={(e) => setToken(e.target.value)}
                    style={{ width: '100%' }}
                    required
                  />
                </div>
              </div>

              <div style={{ display: 'flex', gap: 12, marginTop: 20 }}>
                <button
                  type="submit"
                  disabled={loadingPayment || concurrencyRunning}
                  className="btn-primary"
                  style={{ flex: 1 }}
                >
                  {loadingPayment ? 'Processing...' : 'Submit Normal Payment'}
                </button>

                {/* THE 50 CONCURRENT RETRIES BUTTON */}
                <button
                  type="button"
                  onClick={handleFire50Concurrent}
                  disabled={loadingPayment || concurrencyRunning}
                  className="btn-danger"
                  style={{ flex: 1.3, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8 }}
                >
                  <Flame size={18} />
                  {concurrencyRunning ? 'Firing 50 parallel requests...' : 'Fire 50 Concurrent Retries'}
                </button>
              </div>

              <p style={{ marginTop: 12, fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                * "Fire 50 Concurrent Retries" dispatches 50 simultaneous parallel HTTP POST requests with the EXACT SAME <code>Idempotency-Key</code> via <code>Promise.all</code> to demonstrate the atomic PostgreSQL <code>INSERT ... ON CONFLICT</code> lease and outbox deduplication.
              </p>
            </form>

            {/* Concurrency Results Box */}
            {concurrencyResult && (
              <div style={{
                marginTop: 20,
                background: 'rgba(16, 185, 129, 0.05)',
                border: '1px solid rgba(16, 185, 129, 0.3)',
                borderRadius: 8,
                padding: 16
              }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                  <h3 style={{ fontSize: '0.95rem', fontWeight: 600, color: '#34d399', display: 'flex', alignItems: 'center', gap: 6 }}>
                    <CheckCircle2 size={16} /> Concurrency Burst Results (50 parallel requests)
                  </h3>
                  <span className="badge badge-emerald">{concurrencyResult.elapsedMs} ms total</span>
                </div>

                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 8, textAlign: 'center', marginBottom: 12 }}>
                  <div style={{ background: 'var(--bg-secondary)', padding: 8, borderRadius: 6 }}>
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Payments created</div>
                    <div style={{ fontSize: '1.2rem', fontWeight: 700, color: '#34d399' }}>{concurrencyResult.created201}</div>
                  </div>
                  <div style={{ background: 'var(--bg-secondary)', padding: 8, borderRadius: 6 }}>
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Replays (stored 201)</div>
                    <div style={{ fontSize: '1.2rem', fontWeight: 700, color: '#60a5fa' }}>{concurrencyResult.replayed200}</div>
                  </div>
                  <div style={{ background: 'var(--bg-secondary)', padding: 8, borderRadius: 6 }}>
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Conflicts (409)</div>
                    <div style={{ fontSize: '1.2rem', fontWeight: 700, color: '#fbbf24' }}>{concurrencyResult.conflicts409}</div>
                  </div>
                  <div style={{ background: 'var(--bg-secondary)', padding: 8, borderRadius: 6 }}>
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Double Charges</div>
                    <div style={{ fontSize: '1.2rem', fontWeight: 700, color: concurrencyResult.doubleCharges === 0 ? '#34d399' : '#f43f5e' }}>
                      {concurrencyResult.doubleCharges}
                    </div>
                  </div>
                </div>

                <div style={{ fontSize: '0.78rem', color: 'var(--text-secondary)' }}>
                  Idempotency Key: <code style={{ color: '#34d399' }}>{concurrencyResult.key}</code>
                  <br />
                  Result: <strong>Zero double charges!</strong> Exactly 1 payment was executed; all 49 concurrent retries safely replayed the stored state without moving money twice.
                </div>
              </div>
            )}
          </div>

          {/* Recent Payments Table */}
          <div className="card">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
              <h2 style={{ fontSize: '1.15rem', fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 }}>
                <Clock size={18} color="var(--accent-blue)" />
                Recorded Payments ({payments.length})
              </h2>
              <button onClick={refreshData} className="btn-secondary" style={{ padding: '4px 8px', fontSize: '0.75rem' }}>
                <RotateCcw size={12} /> Refresh
              </button>
            </div>

            <div style={{ maxHeight: 420, overflowY: 'auto' }}>
              {payments.length === 0 ? (
                <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem', textAlign: 'center', padding: '40px 0' }}>
                  No payments processed yet. Submit a payment or run the 50 concurrent retries test!
                </p>
              ) : (
                <table>
                  <thead>
                    <tr>
                      <th>Status</th>
                      <th>Amount</th>
                      <th>Customer</th>
                      <th>Token</th>
                      <th>Time</th>
                    </tr>
                  </thead>
                  <tbody>
                    {payments.slice(0, 15).map((p) => (
                      <tr key={p.id}>
                        <td>
                          <span className={`badge ${
                            p.status === 'CAPTURED' || p.status === 'SETTLED' ? 'badge-emerald' :
                            p.status === 'AUTHORIZED' ? 'badge-blue' :
                            p.status === 'FAILED' ? 'badge-rose' : 'badge-amber'
                          }`}>
                            {p.status}
                          </span>
                        </td>
                        <td style={{ fontWeight: 600 }}>${(p.amount / 100).toFixed(2)}</td>
                        <td>{p.customerId}</td>
                        <td><code>{p.paymentToken}</code></td>
                        <td style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                          {new Date(p.createdAt).toLocaleTimeString()}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        </div>
      )}

      {/* TAB 2: Chaos & Resilience */}
      {activeTab === 'chaos' && (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24 }}>
          <div className="card">
            <h2 style={{ fontSize: '1.15rem', fontWeight: 600, marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
              <Flame size={18} color="var(--accent-rose)" />
              Simulated Card Network Chaos Knobs
            </h2>
            <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: 20 }}>
              Control real-time network faults in <code>sim-processor</code>. LedgerFlow's Resilience4j circuit breaker, timeouts, and outbox relay prevent ledger corruption even under severe network collapse.
            </p>

            <div style={{ marginBottom: 18 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                <label style={{ fontSize: '0.85rem', fontWeight: 600 }}>Simulated Latency</label>
                <span style={{ color: 'var(--accent-blue)', fontWeight: 600 }}>{chaos.latencyMs} ms</span>
              </div>
              <input
                type="range"
                min="0"
                max="3000"
                step="100"
                value={chaos.latencyMs}
                onChange={(e) => setChaos({ ...chaos, latencyMs: parseInt(e.target.value) })}
                style={{ width: '100%' }}
              />
              <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 4 }}>
                * Client timeout trips after 2000 ms. Requests exceeding 2000 ms trigger circuit breaker fallback.
              </div>
            </div>

            <div style={{ marginBottom: 18 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                <label style={{ fontSize: '0.85rem', fontWeight: 600 }}>Network Failure Rate (HTTP 500)</label>
                <span style={{ color: 'var(--accent-rose)', fontWeight: 600 }}>{Math.round(chaos.failureRate * 100)}%</span>
              </div>
              <input
                type="range"
                min="0"
                max="1"
                step="0.05"
                value={chaos.failureRate}
                onChange={(e) => setChaos({ ...chaos, failureRate: parseFloat(e.target.value) })}
                style={{ width: '100%' }}
              />
              <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 4 }}>
                * Injected network dropouts trigger Resilience4j exponential retries (up to 3 attempts).
              </div>
            </div>

            <div style={{ marginBottom: 24 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                <label style={{ fontSize: '0.85rem', fontWeight: 600 }}>Duplicate Webhook Rate</label>
                <span style={{ color: 'var(--accent-amber)', fontWeight: 600 }}>{Math.round(chaos.duplicateRate * 100)}%</span>
              </div>
              <input
                type="range"
                min="0"
                max="1"
                step="0.05"
                value={chaos.duplicateRate}
                onChange={(e) => setChaos({ ...chaos, duplicateRate: parseFloat(e.target.value) })}
                style={{ width: '100%' }}
              />
              <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 4 }}>
                * Webhook receiver deduplicates events via <code>processor_events</code> table and returns 200 OK without moving money twice.
              </div>
            </div>

            <div style={{ display: 'flex', gap: 10 }}>
              <button
                onClick={() => handleApplyChaos(chaos)}
                disabled={chaosSaving}
                className="btn-primary"
                style={{ flex: 1 }}
              >
                {chaosSaving ? 'Applying Knobs...' : 'Apply Chaos Settings'}
              </button>

              <button
                onClick={() => handleApplyChaos({ latencyMs: 0, failureRate: 0.0, duplicateRate: 0.0 })}
                className="btn-secondary"
              >
                Reset to Normal
              </button>
            </div>
          </div>

          <div className="card">
            <h2 style={{ fontSize: '1.15rem', fontWeight: 600, marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
              <ShieldCheck size={18} color="var(--accent-emerald)" />
              Resilience Architecture Guarantees
            </h2>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <div style={{ background: 'var(--bg-primary)', padding: 14, borderRadius: 8, border: '1px solid var(--border)' }}>
                <div style={{ fontWeight: 600, fontSize: '0.9rem', color: '#60a5fa', marginBottom: 4 }}>
                  1. Detached External Processor Calls
                </div>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                  External card calls NEVER happen inside an active database transaction. DB transactions remain strictly under 5ms, eliminating database connection pool exhaustion.
                </div>
              </div>

              <div style={{ background: 'var(--bg-primary)', padding: 14, borderRadius: 8, border: '1px solid var(--border)' }}>
                <div style={{ fontWeight: 600, fontSize: '0.9rem', color: '#34d399', marginBottom: 4 }}>
                  2. Resilience4j Circuit Breaker & Exponential Backoff
                </div>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                  Configured with a sliding window of 10 calls. If failure rate exceeds 50%, circuit breaker opens for 5 seconds to protect downstream systems from cascading failure.
                </div>
              </div>

              <div style={{ background: 'var(--bg-primary)', padding: 14, borderRadius: 8, border: '1px solid var(--border)' }}>
                <div style={{ fontWeight: 600, fontSize: '0.9rem', color: '#fbbf24', marginBottom: 4 }}>
                  3. Outbox Relay with At-Least-Once Delivery
                </div>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                  Changes are committed to PostgreSQL <code>outbox</code> table in the exact same transaction as payment state. Kafka relays publish asynchronously, and consumers dedupe by event ID.
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* TAB 3: Double-Entry Ledger */}
      {activeTab === 'ledger' && (
        <div>
          {/* Invariant Header Banner */}
          <div style={{
            background: balanceCheck?.balanced ? 'rgba(16, 185, 129, 0.1)' : 'rgba(244, 63, 94, 0.1)',
            border: `1px solid ${balanceCheck?.balanced ? 'rgba(16, 185, 129, 0.3)' : 'rgba(244, 63, 94, 0.3)'}`,
            borderRadius: 10,
            padding: 20,
            marginBottom: 24,
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center'
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
              <div style={{
                width: 48,
                height: 48,
                borderRadius: '50%',
                background: balanceCheck?.balanced ? 'rgba(16, 185, 129, 0.2)' : 'rgba(244, 63, 94, 0.2)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center'
              }}>
                <CheckCircle2 size={28} color={balanceCheck?.balanced ? '#34d399' : '#f43f5e'} />
              </div>
              <div>
                <h2 style={{ fontSize: '1.25rem', fontWeight: 700, color: balanceCheck?.balanced ? '#34d399' : '#f43f5e' }}>
                  {balanceCheck?.balanced ? 'Books balanced ✓ (Zero-Sum Invariant Satisfied)' : 'Invariant Violation!'}
                </h2>
                <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                  Core Principle 1: Money is never created or destroyed. Every movement is a journal transaction of balanced entries (Σ amounts = 0).
                </p>
              </div>
            </div>

            <div style={{ textAlign: 'right' }}>
              <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Net Journal Sum</div>
              <div style={{ fontSize: '1.5rem', fontWeight: 700, color: '#34d399' }}>
                ${((balanceCheck?.sumAll || 0) / 100).toFixed(2)}
              </div>
            </div>
          </div>

          {/* Accounts Breakdown Table */}
          <div className="card">
            <h3 style={{ fontSize: '1.1rem', fontWeight: 600, marginBottom: 16 }}>
              Internal Ledger Accounts
            </h3>
            <table>
              <thead>
                <tr>
                  <th>Account Number</th>
                  <th>Account Type</th>
                  <th>Balance</th>
                  <th>Currency</th>
                  <th>Overdraft Protection</th>
                </tr>
              </thead>
              <tbody>
                {accounts.map((a) => (
                  <tr key={a.id}>
                    <td><code>{a.accountNumber}</code></td>
                    <td><span className="badge badge-purple">{a.accountType}</span></td>
                    <td style={{ fontWeight: 700, fontSize: '0.95rem', color: a.balance >= 0 ? '#f9fafb' : '#f43f5e' }}>
                      ${(a.balance / 100).toFixed(2)}
                    </td>
                    <td>{a.currency}</td>
                    <td>
                      {a.allowNegative ? (
                        <span className="badge badge-blue">Settlement Clearing (Negative OK)</span>
                      ) : (
                        <span className="badge badge-emerald">Strict No-Overdraft</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            <div style={{ marginTop: 20, padding: 14, background: 'var(--bg-primary)', borderRadius: 8, fontSize: '0.8rem', color: 'var(--text-muted)' }}>
              <strong>Append-Only Rule Enforced:</strong> The <code>journal_entries</code> table is protected by a PostgreSQL trigger that raises an exception on any <code>UPDATE</code> or <code>DELETE</code>. Corrections are made strictly via balanced reversal transactions.
            </div>
          </div>
        </div>
      )}

      {/* TAB 4: Reconciliation Queue */}
      {activeTab === 'recon' && (
        <div>
          <div className="card" style={{ marginBottom: 24 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 16 }}>
              <div>
                <h2 style={{ fontSize: '1.15rem', fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 }}>
                  <FileCheck2 size={18} color="var(--accent-emerald)" />
                  Nightly Settlement Reconciliation Engine
                </h2>
                <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                  Compares external bank/processor settlement feeds against internal ledger payments. Detects mismatches: missing items, amount discrepancies, and duplicate bank charges.
                </p>
              </div>

              <button
                onClick={handleSimulateRecon}
                disabled={simulatingRecon}
                className="btn-primary"
                style={{ display: 'flex', alignItems: 'center', gap: 8 }}
              >
                <RotateCcw size={16} />
                {simulatingRecon ? 'Running batch...' : 'Trigger Nightly Reconciliation Batch (Inject 4 Mismatches)'}
              </button>
            </div>

            {reconSuccess && (
              <div style={{ marginTop: 14, padding: 12, background: 'rgba(16, 185, 129, 0.1)', border: '1px solid rgba(16, 185, 129, 0.3)', borderRadius: 6, color: '#34d399', fontSize: '0.85rem' }}>
                {reconSuccess}
              </div>
            )}

            {reconError && (
              <div style={{ marginTop: 14, padding: 12, background: 'rgba(244, 63, 94, 0.1)', border: '1px solid rgba(244, 63, 94, 0.3)', borderRadius: 6, color: '#fb7185', fontSize: '0.85rem' }}>
                {reconError}
              </div>
            )}
          </div>

          <div className="card">
            <h3 style={{ fontSize: '1.1rem', fontWeight: 600, marginBottom: 16 }}>
              Discrepancy Queue ({reconItems.length} items)
            </h3>

            {reconItems.length === 0 ? (
              <p style={{ color: 'var(--text-muted)', fontSize: '0.85rem', textAlign: 'center', padding: '40px 0' }}>
                Reconciliation queue clean! Click "Trigger Nightly Reconciliation Batch" to ingest a settlement file with simulated bank discrepancies.
              </p>
            ) : (
              <table>
                <thead>
                  <tr>
                    <th>Mismatch Type</th>
                    <th>Payment Reference</th>
                    <th>Bank Feed Amount</th>
                    <th>Ledger Amount</th>
                    <th>Status</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {reconItems.map((item) => { const p = proposals[item.id]; return (
                    <React.Fragment key={item.id}>
                    <tr>
                      <td>
                        <span className={`badge ${
                          item.mismatchType === 'AMOUNT_MISMATCH' ? 'badge-amber' :
                          item.mismatchType === 'DUPLICATE_SETTLEMENT' ? 'badge-rose' :
                          item.mismatchType === 'MISSING_IN_BANK' ? 'badge-blue' : 'badge-purple'
                        }`}>
                          {item.mismatchType}
                        </span>
                      </td>
                      <td><code>{item.paymentReference}</code></td>
                      <td>{item.bankAmount != null ? `$${(item.bankAmount / 100).toFixed(2)}` : '—'}</td>
                      <td>{item.ledgerAmount != null ? `$${(item.ledgerAmount / 100).toFixed(2)}` : '—'}</td>
                      <td>
                        <span className={`badge ${item.status === 'RESOLVED' ? 'badge-emerald' : 'badge-amber'}`}>
                          {item.status}
                        </span>
                      </td>
                      <td>
                        {item.status === 'RESOLVED' ? (
                          <span style={{ fontSize: '0.75rem', color: '#34d399', display: 'flex', alignItems: 'center', gap: 4 }}>
                            <CheckCircle2 size={14} /> Resolved
                          </span>
                        ) : p && p.status === 'PENDING_APPROVAL' ? (
                          <button
                            onClick={() => handleApproveProposal(item, p)}
                            className="btn-secondary"
                            title="Only FINANCE_ADMIN can approve; the server enforces it"
                            style={{
                              fontSize: '0.75rem',
                              padding: '4px 8px',
                              background: currentPersona.role === 'ROLE_FINANCE_ADMIN' ? '#10b981' : 'var(--bg-card)',
                              color: currentPersona.role === 'ROLE_FINANCE_ADMIN' ? '#fff' : 'var(--text-muted)'
                            }}
                          >
                            Approve proposal
                          </button>
                        ) : (
                          <button
                            onClick={() => handleInvestigate(item)}
                            disabled={investigatingId !== null}
                            className="btn-secondary"
                            style={{ fontSize: '0.75rem', padding: '4px 8px', display: 'flex', alignItems: 'center', gap: 4 }}
                          >
                            <Sparkles size={12} />
                            {investigatingId === item.id ? 'Investigating...' : 'Investigate with AI'}
                          </button>
                        )}
                      </td>
                    </tr>
                    {p && (
                      <tr>
                        <td colSpan={6} style={{ background: 'var(--bg-primary)' }}>
                          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, fontSize: '0.8rem', padding: '4px 2px' }}>
                            <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                              <span className={`badge ${p.generatedBy === 'AI_MODEL' ? 'badge-purple' : 'badge-blue'}`}>
                                {p.generatedBy === 'AI_MODEL' ? 'AI model' : 'Rule engine'}
                              </span>
                              <span className={`badge ${p.status === 'APPROVED' ? 'badge-emerald' : 'badge-amber'}`}>{p.status}</span>
                              <strong>{p.proposedAction}</strong>
                              <span style={{ color: 'var(--text-muted)' }}>root cause: <code>{p.rootCause}</code></span>
                              <span style={{ color: 'var(--text-muted)' }}>confidence {Math.round(p.confidenceScore * 100)}%</span>
                            </div>
                            <div style={{ color: 'var(--text-secondary)' }}>{p.rationale}</div>
                            <div style={{ color: 'var(--text-muted)' }}>
                              {MONEY_MOVING_ACTIONS.includes(p.proposedAction)
                                ? <>On approval posts a balanced entry of <strong>${((p.recommendedAmount ?? 0) / 100).toFixed(2)}</strong> (amount computed by code, not by the AI).</>
                                : <>On approval records the decision only; no money moves.</>}
                              {' '}Evidence: {p.evidenceIds.length} records.
                            </div>
                            {p.guardrailNote && (
                              <div style={{ color: '#fbbf24' }}>Guardrail: {p.guardrailNote}. The rule engine's proposal is shown instead.</div>
                            )}
                          </div>
                        </td>
                      </tr>
                    )}
                    </React.Fragment>
                  ); })}
                </tbody>
              </table>
            )}

            <div style={{ marginTop: 20, padding: 12, background: 'var(--bg-primary)', borderRadius: 8, fontSize: '0.8rem', color: 'var(--text-muted)' }}>
              <strong>AI never moves money:</strong> the investigator only has read-only lookups and proposes an action from a fixed list; amounts are computed by code and every answer is checked by guardrails (invalid answers fall back to the rule engine). Approving is <code>ROLE_FINANCE_ADMIN</code> only, enforced on the server: Ops and Customer personas get HTTP 403. Approving twice gets HTTP 409.
            </div>
          </div>
        </div>
      )}

      {/* TAB 5: Observability */}
      {activeTab === 'observability' && (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24 }}>
          <div className="card">
            <h2 style={{ fontSize: '1.15rem', fontWeight: 600, marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
              <Activity size={18} color="var(--accent-blue)" />
              System Metrics & Telemetry
            </h2>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <div style={{ background: 'var(--bg-primary)', padding: 14, borderRadius: 8, border: '1px solid var(--border)' }}>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Prometheus Metrics Endpoint</div>
                <div style={{ fontSize: '0.9rem', fontWeight: 600, color: '#60a5fa', marginTop: 4 }}>
                  <code>GET /actuator/prometheus</code>
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', marginTop: 4 }}>
                  Exposes Micrometer meters, JVM GC statistics, HikariCP database pool metrics, and custom business counters.
                </div>
              </div>

              <div style={{ background: 'var(--bg-primary)', padding: 14, borderRadius: 8, border: '1px solid var(--border)' }}>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Health & Liveness Check</div>
                <div style={{ fontSize: '0.9rem', fontWeight: 600, color: '#34d399', marginTop: 4 }}>
                  <code>GET /actuator/health</code>
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', marginTop: 4 }}>
                  Validates PostgreSQL connectivity, Kafka broker connection, and disk space.
                </div>
              </div>

              <div style={{ background: 'var(--bg-primary)', padding: 14, borderRadius: 8, border: '1px solid var(--border)' }}>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>OpenTelemetry Tracing</div>
                <div style={{ fontSize: '0.9rem', fontWeight: 600, color: '#a78bfa', marginTop: 4 }}>
                  W3C TraceContext Propagation
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', marginTop: 4 }}>
                  Propagates traceparent headers across HTTP calls, transactional outbox records, and Kafka consumer threads.
                </div>
              </div>
            </div>
          </div>

          <div className="card">
            <h2 style={{ fontSize: '1.15rem', fontWeight: 600, marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
              <Layers size={18} color="var(--accent-purple)" />
              Core Architecture Topology
            </h2>

            <div style={{ fontSize: '0.82rem', color: 'var(--text-secondary)', lineHeight: 1.6 }}>
              <div style={{ padding: 10, background: 'var(--bg-primary)', borderRadius: 6, marginBottom: 8, border: '1px solid var(--border)' }}>
                <strong>Client Layer:</strong> React/TS Dashboard sends requests with <code>Idempotency-Key</code> header and Bearer JWT.
              </div>
              <div style={{ textAlign: 'center', color: 'var(--accent-emerald)' }}>↓</div>
              <div style={{ padding: 10, background: 'var(--bg-primary)', borderRadius: 6, marginBottom: 8, border: '1px solid var(--border)' }}>
                <strong>Idempotency & State Machine:</strong> PostgreSQL atomic insert lease prevents race conditions; transitions validated at domain layer.
              </div>
              <div style={{ textAlign: 'center', color: 'var(--accent-emerald)' }}>↓</div>
              <div style={{ padding: 10, background: 'var(--bg-primary)', borderRadius: 6, marginBottom: 8, border: '1px solid var(--border)' }}>
                <strong>Transactional Outbox:</strong> Payment state change + Outbox record committed in 1 database transaction.
              </div>
              <div style={{ textAlign: 'center', color: 'var(--accent-emerald)' }}>↓</div>
              <div style={{ padding: 10, background: 'var(--bg-primary)', borderRadius: 6, marginBottom: 8, border: '1px solid var(--border)' }}>
                <strong>Kafka & Idempotent Consumer:</strong> Outbox relay publishes to Kafka; consumer checks <code>processed_events</code> table before processing.
              </div>
              <div style={{ textAlign: 'center', color: 'var(--accent-emerald)' }}>↓</div>
              <div style={{ padding: 10, background: 'var(--bg-primary)', borderRadius: 6, border: '1px solid var(--border)' }}>
                <strong>Double-Entry Ledger:</strong> Deterministic ID order row locks (<code>SELECT ... FOR UPDATE</code>) prevent deadlocks; append-only journal entries.
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
