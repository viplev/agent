# CLAUDE.md

## Project Description

### VIPLEV Agent

A lightweight component installed in the environment to be benchmarked. The agent communicates with VIPLEV via the REST API and receives instructions on which tasks to execute. To carry out these tasks, the agent requires administrative access to the underlying containerization layer — primarily Docker and Kubernetes.

The agent's responsibilities include:
- Monitoring resource usage for individual containers/pods and the overall system (CPU, memory, network, etc.)
- Starting and stopping load testing tools (e.g. K6) based on user-defined scenarios
- Simulating container failures by controlled shutdown of selected containers/pods

The communication flow is as follows: the user defines a test scenario via the UI or REST API, VIPLEV (another project) stores and coordinates the scenario, and the agent executes it in the associated environment. Test data is continuously collected by the agent and made available to the user through VIPLEV.
