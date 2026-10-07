import os

SANDBOX_DIR = "sc-sandbox/src/main/java/com/swarmcoder/sandbox"
os.makedirs(SANDBOX_DIR, exist_ok=True)

models = {
    "DockerSandboxManager.java": """package com.swarmcoder.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DockerClientBuilder;
import java.util.UUID;

public class DockerSandboxManager {
    private final DockerClient dockerClient;

    public DockerSandboxManager() {
        this.dockerClient = DockerClientBuilder.getInstance().build();
    }

    public String launchContainer(String image, String worktreeHostPath, String writeSetEnv) {
        String containerName = "swarmcoder-worker-" + UUID.randomUUID().toString().substring(0, 8);
        
        HostConfig hostConfig = HostConfig.newHostConfig()
            .withBinds(new Bind(worktreeHostPath, new Volume("/workspace")));

        CreateContainerResponse container = dockerClient.createContainerCmd(image)
            .withName(containerName)
            .withHostConfig(hostConfig)
            .withEnv("SC_WRITE_SET=" + writeSetEnv)
            .withCmd("java", "-jar", "/opt/action-server.jar")
            .exec();

        dockerClient.startContainerCmd(container.getId()).exec();
        
        return container.getId();
    }

    public void killContainer(String containerId) {
        try {
            dockerClient.killContainerCmd(containerId).exec();
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception e) {
            // ignore
        }
    }
}"""
}

for filename, content in models.items():
    filepath = os.path.join(SANDBOX_DIR, filename)
    with open(filepath, "w") as f:
        f.write(content)

print(f"Generated {len(models)} sandbox files.")
