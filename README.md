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

### Execution

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
```

---

##  DoguPipe

`DoguPipe.groovy` is a concrete subclass of `BasePipe` tailored to build and release **Dogu** containers.

### Constructor
```groovy
DoguPipe(script, Map config)
```

### Main Method

#### `addDefaultStages()`

Registers all built-in **Dogu Stage Modules** into agent-scoped `StageGroup`s.

Instead of hardcoding stages, `addDefaultStages()` composes the pipeline by loading
independent **stage modules** that each contribute their own stages:

| Module | Responsibility |
|-------|----------------|
| `StaticStages` | Linting, shellcheck, markdown, Sonar, unit tests |
| `IntegrationStages` | Provisioning, setup, build, Trivy, integration tests |
| `MultinodeStages` | Multi-VM / multi-cluster integration tests |
| `ReleaseStages` | Triggers the gitflow via pipeline when Integration Mode is RELEASE |

Internally this looks like:

```groovy
addStageGroup(agentStatic)   { new StaticStages().register(this, it) }
addStageGroup(agentVagrant) { new IntegrationStages().register(this, it) }
addStageGroup(agentVagrant) { new ReleaseStages().register(this, it) }
addStageGroup(agentMultinode){ new MultinodeStages().register(this, it) }
```

#### `DoguConfig`

`DoguConfig` is the compiled, runtime-ready configuration for a Dogu pipeline.
It takes the raw Jenkinsfile config map, applies defaults and normalization,
instantiates all required build systems (Git, EcoSystem, Docker, Vagrant, etc.),
and injects pipeline-specific helpers into them so that the pipeline runs in a
fully initialized, self-contained build environment.

In addition, `DoguPipe` transparently exposes all properties of `DoguConfig`
via Groovy’s `propertyMissing` mechanism.

This means:

```groovy
pipe.ecoSystem
pipe.git
pipe.cypressImage
pipe.runIntegrationTests
```

```groovy
pipe.config.ecoSystem
pipe.config.git
pipe.config.cypressImage
pipe.config.runIntegrationTests
```

but without the caller needing to know or care that a DoguConfig object exists.

This makes DoguPipe behave like a live view of the pipeline configuration
while still keeping all configuration, defaults, and runtime wiring isolated
inside DoguConfig.

### Additional Utilities

- `executeShellTests()`: Runs Bats-based shell tests.
- `runCypress()`: Runs Cypress integration tests.
- `setBuildProperties(List customParams)`: Configures Jenkins parameters.
- `checkout_updatemakefiles(boolean updateSubmodules)`: Checks out code and updates Makefiles with the latest version.

---

## Basic Usage in Jenkinsfile

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

> **Library versioning & class resolution**
>
> The versions of `pipe-build-lib`, `ces-build-lib`, and `dogu-build-lib` are **not defined in the Jenkinsfile**.  
> They are resolved by Jenkins via **Manage Jenkins → Global Pipeline Libraries**, which pins each library to a specific Git ref (branch, tag, or commit).
>
> Jenkins loads each shared library into its **own classloader**.  
> Because of this, **classes from another library are *not visible* unless they are imported**.
>
> That means this will crash:
>
> ```groovy
> // ❌ Will fail if ces-build-lib is not imported
> new com.cloudogu.ces.cesbuildlib.Docker(this)
> ```
>
> And this is the correct way:
>
> ```groovy
> @Library(['pipe-build-lib', 'ces-build-lib', 'dogu-build-lib']) _
>
> import com.cloudogu.ces.cesbuildlib.Docker
>
> new Docker(this)
> ```
>
> Even though the class has a fully-qualified name, **Jenkins will not load it unless the library is explicitly imported**.
> Fully-qualified names avoid ambiguity, but they do not bypass Jenkins’ library isolation.

---


## 🖥 Agent-Based Stage Groups

Stages in PipeBuildLib are not executed individually --- they are
grouped into **StageGroups**, and each group is bound to a specific
**Jenkins agent label**.

``` groovy
addStageGroup(agentStatic) { group -> ... }
addStageGroup(agentVagrant) { group -> ... }
addStageGroup(agentMultinode) { group -> ... }
```

Each `StageGroup` represents **one execution lane on one Jenkins
agent**.

### Execution Model

  -----------------------------------------------------------------------
  Scenario                 What happens
  ------------------------ ----------------------------------------------
  Two groups with          Run in **parallel** on different machines
  **different agent        
  labels**                 

  Two groups with the      Run **sequentially** on the same machine
  **same agent label**     

  Multiple stages inside   Run in the order they were registered
  one group                
  -----------------------------------------------------------------------

### Why this exists

Some parts of a Dogu build: - Must share a workspace (same agent) - Must
not run in parallel (race conditions) - Need powerful machines (Vagrant,
Docker, GCP)

Other parts: - Are safe to run independently - Should run in parallel to
save time

`StageGroup` encodes that **infrastructure intent** directly into the
pipeline.

### Mental Model

Think of a `StageGroup` as:

> "A queue of stages that must run on the same machine."

Different queues → different machines → parallel execution.

This is what makes PipeBuildLib scale across Jenkins agents without
turning into chaos.

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

### Full Example Usage in Jenkins with overriding

```groovy
#!groovy
@Library([
  'pipe-build-lib',
  'ces-build-lib',
  'dogu-build-lib'
]) _

def pipe = new com.cloudogu.sos.pipebuildlib.DoguPipe(this, [
    doguName           : 'redmine',
    shellScripts       : ['''
                          resources/startup.sh
                          resources/post-upgrade.sh
                          resources/pre-upgrade.sh
                          resources/util.sh
                          resources/upgrade-notification.sh
                          resources/default-config.sh
                          resources/update-password-policy.sh
                          resources/util.sh
                          resources/delete-plugin.sh
                          '''],
    dependencies       : ['cas', 'usermgt', 'postgresql'],
    doBatsTests        : true,
    runIntegrationTests: true,
    cypressImage       : "cypress/included:13.14.2"
])
com.cloudogu.ces.dogubuildlib.EcoSystem ecoSystem = pipe.ecoSystem

pipe.setBuildProperties()
pipe.addDefaultStages()
pipe.overrideStage('Setup') {
  ecoSystem.loginBackend('cesmarvin-setup')
  ecoSystem.setup([additionalDependencies: ['official/postgresql']])
}

pipe.run()
```


### Additional Dogu Dependencies Setup Stage


```groovy
pipe.overrideStage('Setup') {
    ecoSystem.loginBackend('cesmarvin-setup')
    def settingsJson = '{"custom_menu_entries":[{"name":"Handbuch \uD83D\uDD17","url":"https://docs.cloudogu.com/de/usermanual/easyredmine/er12/1_administrators_checklist/","icon":"icon-help"}]}'
    def escapedSettings = settingsJson.replaceAll('"', '\\\\"')

    ecoSystem.setup([
        additionalDependencies: ['official/mysql', 'official/redis'],
        // setting custom menu entry for docs
        registryConfig: """
            "easyredmine": {
                "logging": {
                    "root": "ERROR"
                },
                "default_data": {
                    "usertype_settings": "${escapedSettings}"
                }
            },
            "_global": {
                "password-policy": {
                    "must_contain_capital_letter": "false",
                    "must_contain_lower_case_letter": "true",
                    "must_contain_digit": "true",
                    "must_contain_special_character": "false",
                    "min_length": "1"
                }
            }
        """
    ])
}
```