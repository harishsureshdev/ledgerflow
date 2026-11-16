package com.ledgerflow.investigator;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * A stand-in for a real LLM (Ollama/Bedrock) so tests are deterministic and need no network.
 * Each test scripts the raw text the "model" returns, including wrong or malicious answers.
 */
public class ScriptedChatModel implements ChatModel {

    private volatile Function<Prompt, String> responder = p -> {
        throw new IllegalStateException("no script set");
    };
    private final AtomicReference<Prompt> lastPrompt = new AtomicReference<>();

    public void respondWith(String rawModelOutput) {
        this.responder = p -> rawModelOutput;
    }

    public void failWith(RuntimeException e) {
        this.responder = p -> {
            throw e;
        };
    }

    public Prompt lastPrompt() {
        return lastPrompt.get();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        lastPrompt.set(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(responder.apply(prompt)))));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        // Real models' options implement ToolCallingChatOptions; without it ChatClient drops the tools.
        return ToolCallingChatOptions.builder().build();
    }
}
