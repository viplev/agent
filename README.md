# viplev-agent


## Commit message convention

This project uses [Conventional Commits](https://www.conventionalcommits.org/) and [semantic-release](https://github.com/semantic-release/semantic-release) for automated versioning and changelog generation.

### Format

```
<type>(<optional scope>): <description>

<optional body>

<optional footer>
```

### Types

| Type | Description | Version bump |
|------|-------------|--------------|
| `feat` | New feature or functionality | Minor |
| `fix` | Bug fix | Patch |
| `docs` | Documentation only | None |
| `chore` | Build, CI, tooling, dependencies | None |
| `refactor` | Code change that neither fixes a bug nor adds a feature | None |
| `test` | Adding or updating tests | None |
| `perf` | Performance improvement | Patch |

A commit with `BREAKING CHANGE:` in the footer (or `!` after the type) triggers a **major** version bump.

### Examples

```
feat: add container monitoring endpoint
fix: correct Docker socket permissions
docs: update README with commit conventions
chore: upgrade Spring Boot to 3.5.13
refactor(monitoring): extract metrics collection to separate service
feat!: change agent polling API response format
```

### Pull requests

- PR title must follow the same Conventional Commits format — it becomes the merge commit message
- Keep the title short (under 72 characters), use the description for details
- PR description should include:
  - **Summary** — what changed and why (2-3 bullet points)
  - **Test plan** — how to verify the changes

## Run with Gradle

The project uses the [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html), which is included in the repository. No separate Gradle installation is required.

### Prerequisites

- Java 21 (managed via Gradle [toolchain](https://docs.gradle.org/current/userguide/toolchains.html))

### Common commands

```bash
# Start the application locally
./gradlew bootRun

# Run all tests
./gradlew test

# Compile without running tests
./gradlew classes

# Full build (compile + test + jar)
./gradlew build

# Build only the boot jar (output: build/libs/viplev-agent.jar)
./gradlew bootJar
```

### Cleanup and troubleshooting

```bash
# Delete build output
./gradlew clean

# Clean build from scratch
./gradlew clean build

# List all available tasks
./gradlew tasks

# Show dependency tree (useful for finding version conflicts)
./gradlew dependencies

# Run build with debug output
./gradlew build --info
```

### Docker

```bash
# Build Docker image
docker build -t viplev-agent .

# Run container with Docker socket access
docker run -p 8080:8080 -v /var/run/docker.sock:/var/run/docker.sock viplev-agent
```
