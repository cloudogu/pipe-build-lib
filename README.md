# PipeBuildLib - Jenkins Shared Library

This repository provides a modular and reusable Jenkins Shared Library to manage pipeline logic using Groovy and a class-based approach.

## Contents

- `BasePipe`: Abstract base pipeline class that defines the structure and behavior for stage management.
- `DoguPipe`: A concrete implementation tailored for Dogu builds.
- `StageDefinition`: Data class for holding individual stage metadata.

---

##  BasePipe

Located in `BasePipe.groovy`, this abstract class provides:

### Constructor
```groovy
BasePipe(script)
```
Initializes the pipeline with a Jenkins `script` context.

---

###  Stage Management

#### `addStage(String name, Closure block, String agentLabel = defaultAgent, boolean parallel = false)`
Adds a new stage to the pipeline.
- `agentLabel`: Optional agent label to execute the stage.
- `parallel`: If true, this stage will be run in parallel with other stages on the same agent.

#### `insertStageAfter(String afterName, String newName, Closure block, String agentLabel = defaultAgent, boolean parallel = false)`
Inserts a stage after a given one.

#### `overrideStage(String name, Closure newBlock, String newAgentLabel = null, Boolean newParallel = null)`
Overrides an existing stage's logic or properties.

#### `removeStage(String name)`
Removes a stage by name.

#### `moveStageAfter(String stageToMove, String targetStage)`
Reorders a stage after another stage.

#### `assignAgentToStage(String name, String agentLabel)`
Assigns or reassigns an agent to a stage.

#### `assignAgents(Map<String, String> assignments)`
Assigns multiple stages to different agents using a map.

---

### ▶️ Execution

#### `run()`
Groups all added stages by their agent and:
- Executes sequential and parallel stages on the appropriate agents.
- Handles empty scripts or stage lists gracefully with debug logs.

---

##  StageDefinition

Found in `StageDefinition.groovy`, used to represent each stage:

```groovy
class StageDefinition {
    String name
    Closure block
    String agentLabel
    boolean parallel
}
```

---

### Default `PipelineMode.FULL` Behavior

All stages added via `StageGroup.stage(...)` are **implicitly executed in `PipelineMode.FULL`**.

This means:
- Any stage registered with `stage(...)` will **always run** when the pipeline is executed in `FULL` mode
- Even if you specify a different `PipelineMode`, `FULL` is automatically added

```groovy
group.stage("Build", PipelineMode.INTEGRATION) { ... }

---


##  DoguPipe

`DoguPipe.groovy` is a concrete subclass of `BasePipe` tailored to build and release **Dogu** containers.

### Constructor
```groovy
DoguPipe(script, Map config)
```

### Components
- Git, GitFlow, GitHub, EcoSystem, Vagrant, Markdown
- Customizable through the `config` map.

### Main Method

#### `addDefaultStages()`
Adds a full set of predefined build/test/release stages.

### Additional Utilities

- `executeShellTests()`: Runs Bats-based shell tests.
- `runCypress()`: Runs Cypress integration tests.
- `setBuildProperties(List customParams)`: Configures Jenkins parameters.
- `checkout_updatemakefiles(boolean updateSubmodules)`: Checks out code and updates Makefiles with the latest version.

---

## ⚙️ Usage in Jenkinsfile

```groovy
@Library('pipebuildlib') _
import com.cloudogu.sos.pipebuildlib.DoguPipe

def pipe = new DoguPipe(this, [
    doguName: 'my-dogu',
    runIntegrationTests: true,
    shellScripts: 'scripts/*.sh'
])

pipe.setBuildProperties()
pipe.addDefaultStages()
pipe.run()
```

---

##  Notes

- Default agent label is `"sos"` if not specified.
- Stages with the same agent can be grouped and run in parallel.
- Ensure your `Jenkinsfile` has access to the `@Library` and the script context (`this`) is passed correctly.

---

## Usage Examples

### Initialize and Run Custom Pipeline

```groovy
class MyCustomPipe extends BasePipe {
    MyCustomPipe(script) {
        super(script)
        addStage("Checkout", {
            script.echo "Checking out source..."
        })

        addStage("Build", {
            script.echo "Building..."
        }, agentLabel: "builder", parallel: false)

        addStage("Test", {
            script.echo "Running tests..."
        }, parallel: true)
    }
}
```

### In Jenkinsfile

```groovy
@Library('your-shared-library') _
def pipe = new MyCustomPipe(this)
pipe.run()
```

### Dynamically Modify Stages

```groovy
pipe.overrideStage("Build", {
    script.echo "Overridden build step"
})

pipe.removeStage("Test")

pipe.insertStageAfter("Checkout", "Lint", {
    script.echo "Linting code..."
})
```

### Full Example Usage in Jenkins with custom stage
```groovy
@Library([
  'pipe-build-lib',
  'ces-build-lib',
  'dogu-build-lib'
]) _

// Create instance of DoguPipe with configuration parameters
def pipe = new com.cloudogu.sos.pipebuildlib.DoguPipe(this, [
    doguName           : "jenkins",

    // Optional behavior settings
    shellScripts       : "resources/startup.sh resources/upgrade-notification.sh resources/pre-upgrade.sh",
    dependencies       : ["cas", "usermgt"],
    checkMarkdown      : true,
    runIntegrationTests: true,
    cypressImage       : "cypress/included:13.16.1"
])

// Set default or custom build parameters (can also pass a list to override defaults)
pipe.setBuildProperties()
// add default stages based on config map
pipe.addDefaultStages()

// Insert a custom post-integration stage directly after the "Integration Tests" stage
pipe.insertStageAfter("Integration Tests", "Test: Change Global Admin Group", {
    def eco = pipe.ecoSystem
    // Change the global admin group and restart jenkins
    eco.changeGlobalAdminGroup("newAdminGroup")
    eco.restartDogu("jenkins")
    eco.waitForDogu("jenkins")

    // Run Cypress tests again without video/screenshot recording
    eco.runCypressIntegrationTests([
        cypressImage     : "cypress/included:13.16.1",
        enableVideo      : false,
        enableScreenshots: false
    ])
})


// Run the pipeline – this will execute all previously added stages
pipe.run()
```
### Full Example Usage in Jenkins with custom stage and overriding
```groovy
@Library([
  'pipe-build-lib',
  'ces-build-lib',
  'dogu-build-lib'
]) _

def pipe = new com.cloudogu.sos.pipebuildlib.DoguPipe(this, [
    doguName           : "portainer",
    shellScripts       : "./resources/startup.sh",
    checkMarkdown      : true,
    doBatsTests        : true,
    runIntegrationTests: true,
    doSonarTests       : true

])

pipe.setBuildProperties()
pipe.addDefaultStages()

pipe.insertStageAfter("Bats Tests","build & test carp") {
    def ctx = pipe.script
    new com.cloudogu.ces.cesbuildlib.Docker(ctx)
        .image('golang:1.23')
        .mountJenkinsUser()
        .inside('-e ENVIRONMENT=ci')
    {
            ctx.sh 'make carp-clean'
            ctx.sh 'make build-carp'
            ctx.sh 'make carp-unit-test'
    }
}

pipe.overrideStage("Integration tests")
{
    com.cloudogu.ces.dogubuildlib.EcoSystem eco = pipe.ecoSystem
    eco.runCypressIntegrationTests([        enableVideo      : params.EnableVideoRecording,
                                            enableScreenshots: params.EnableScreenshotRecording,
                                            cypressImage: pipe.cypressImage])
    // Test special case with restricted access
    eco.vagrant.ssh "sudo etcdctl set /config/portainer/user_access_restricted true"
    eco.restartDogu(pipe.doguName)
    eco.runCypressIntegrationTests([        enableVideo      : params.EnableVideoRecording,
                                            enableScreenshots: params.EnableScreenshotRecording,
                                            cypressImage: pipe.cypressImage,
                                            additionalCypressArgs:
                                            "--config '{\"excludeSpecPattern\": [\"cypress/e2e/dogu_integration_test_lib/*\"]}'"])
}

pipe.run()
```
