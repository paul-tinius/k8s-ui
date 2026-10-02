package com.paultinius.k8s.dashboard.ai;

import com.paultinius.k8s.dashboard.model.AiPreset;

import java.util.List;

public final class AiPresets {

    private AiPresets() {
    }

    public static List<AiPreset> all() {
        return List.of(
                new AiPreset("xai", "Grok", "https://api.x.ai/v1", "grok-4.7", "XAI_API_KEY", true,
                        "Default. OpenAI-compatible chat completions on api.x.ai."),
                new AiPreset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4.1", "OPENAI_API_KEY", true,
                        "Set DASHBOARD_AI_BASE_URL and DASHBOARD_AI_API_KEY on the server."),
                new AiPreset("openrouter-claude", "Claude", "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-4", "OPENROUTER_API_KEY", true,
                        "Claude through an OpenAI-compatible OpenRouter route."),
                new AiPreset("openrouter-gemini", "Gemini", "https://openrouter.ai/api/v1", "google/gemini-2.5-pro", "OPENROUTER_API_KEY", true,
                        "Gemini through an OpenAI-compatible OpenRouter route."),
                new AiPreset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "DEEPSEEK_API_KEY", true,
                        "OpenAI-compatible chat completions."),
                new AiPreset("qwen", "Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "DASHSCOPE_API_KEY", true,
                        "DashScope compatible-mode endpoint."),
                new AiPreset("ollama", "Ollama", "http://127.0.0.1:11434/v1", "llama3.1", "", true,
                        "Local Ollama. Leave the API key empty unless the server requires one."),
                new AiPreset("lmstudio", "LM Studio", "http://127.0.0.1:1234/v1", "local-model", "", true,
                        "LM Studio's local OpenAI-compatible server.")
        );
    }
}
