import os

CONFIG_DIR = "sc-app/src/main/java/com/swarmcoder/app/config"
os.makedirs(CONFIG_DIR, exist_ok=True)

models = {
    "SwarmConfig.java": """import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.List;

public record SwarmConfig(
    @JsonProperty("spark") SparkConfig spark,
    @JsonProperty("cloud") CloudConfig cloud,
    @JsonProperty("roles") RolesConfig roles,
    @JsonProperty("swarm") SwarmEngineConfig swarm,
    @JsonProperty("budgets") BudgetsConfig budgets,
    @JsonProperty("guidelines") GuidelinesConfig guidelines,
    @JsonProperty("sandbox") SandboxConfig sandbox,
    @JsonProperty("overnight") OvernightConfig overnight,
    @JsonProperty("telemetry") TelemetryConfig telemetry
) {}""",
    "SparkConfig.java": """import java.util.List;
public record SparkConfig(List<SparkInstance> instances) {}""",
    "SparkInstance.java": """import com.fasterxml.jackson.annotation.JsonProperty;
public record SparkInstance(String id, String baseUrl, String servedModelName, 
    String toolDialect, int contextCeiling, int kvBytesPerTokenEstimate, int maxNumSeqs) {}""",
    "CloudConfig.java": """import java.util.Map;
public record CloudConfig(Map<String, CloudEndpoint> endpoints) {}""",
    "CloudEndpoint.java": """public record CloudEndpoint(String baseUrl, String apiKey) {}""",
    "AgentModelConfig.java": """public record AgentModelConfig(String protocol, String baseUrl, String apiKey, String modelName) {}""",
    "RolesConfig.java": """import java.util.List;
public record RolesConfig(AgentModelConfig architect, AgentModelConfig testAuthor, AgentModelConfig librarian, 
    AgentModelConfig designReviewer, AgentModelConfig judge, AgentModelConfig approver, List<AgentModelConfig> workerFamilies, AgentModelConfig utility) {}""",
    "SwarmEngineConfig.java": """import com.fasterxml.jackson.annotation.JsonProperty;
public record SwarmEngineConfig(int nPerTask, boolean splitAcrossFamilies, int maxConcurrentTaskGroups, 
    double tempMin, double tempMax, DispatchConfig dispatch) {}""",
    "DispatchConfig.java": """public record DispatchConfig(int staggerMs) {}""",
    "BudgetsConfig.java": """public record BudgetsConfig(long maxCloudTokensPerRun, long maxLocalTokensPerTask, int wallClockCeilingHours) {}""",
    "GuidelinesConfig.java": """public record GuidelinesConfig(boolean autoPromote, int decayRuns, int maxPrefixTokens) {}""",
    "SandboxConfig.java": """public record SandboxConfig(int cpus, int memGb, int poolExtra) {}""",
    "OvernightConfig.java": """public record OvernightConfig(boolean enabled) {}""",
    "TelemetryConfig.java": """public record TelemetryConfig(String otlpEndpoint) {}""",
    "ConfigLoader.java": """import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;

public class ConfigLoader {
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    public static SwarmConfig loadDefaultConfig() throws IOException {
        Path configPath = Paths.get(System.getProperty("user.home"), ".swarmcoder", "config.yaml");
        if (configPath.toFile().exists()) {
            return YAML_MAPPER.readValue(configPath.toFile(), SwarmConfig.class);
        } else {
            throw new IOException("Config file not found at " + configPath);
        }
    }
}"""
}

for filename, content in models.items():
    filepath = os.path.join(CONFIG_DIR, filename)
    with open(filepath, "w") as f:
        f.write("package com.swarmcoder.app.config;\n\n")
        f.write(content + "\n")

print(f"Generated {len(models)} config records.")
