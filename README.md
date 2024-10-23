# J-Scheduler Documentation

## Table of Contents
- [Introduction](#introduction)
- [Features](#features)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Usage](#usage)
    - [Task Scheduling](#task-scheduling)
    - [Task Prioritization](#task-prioritization)
    - [Error Handling](#error-handling)
    - [Task Logging](#task-logging)
    - [Shutdown](#shutdown)
- [Examples](#examples)
- [Contributing](#contributing)
- [License](#license)


## Introduction
J-Scheduler is a lightweight, Java-based library that allows developers to easily schedule background tasks with minimal setup. This framework offers configurable task prioritization, error handling, logging, and customizable scheduling options, all within a simple, intuitive API.



## Features
- Zero-config out-of-the-box setup with default thread pool size.
- Task prioritization to ensure critical tasks execute first.
- Simple API for scheduling tasks with delays or recurring intervals.
- Integrated error handling and logging for task execution failures.
- Graceful shutdown for managing resources properly.
- Lightweight and efficient, designed for easy integration into any project.



## Installation
You can install the **Zero-config Background Task Framework** in 2 simple steps:

### Gradle
1. At your `build.gradle` or `build.gradle.kts` file, add the following at the end of repositories:
```kotlin
  repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
  }
```
2. And then add the dependency:
```kotlin
dependencies {
    implementation 'com.github.Voraes:j-scheduler:v1.1'
}
```

### Maven
1. Add the repository to your `pom.xml`:
```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>
```
2. And then add the dependency:
```xml
<dependency>
  <groupId>com.github.Voraes</groupId>
  <artifactId>j-scheduler</artifactId>
  <version>v1.1</version>
</dependency>
```



## Quick Start
### Basic Setup
```java
import org.java.scheduler.BackgroundTaskScheduler;
import java.util.concurrent.TimeUnit;

public class TaskDemo {
    public static void main(String[] args) {
        BackgroundTaskScheduler scheduler = new BackgroundTaskScheduler(3); //Thread pool size

        // Schedule a simple task with a delay of 5 seconds
        scheduler.scheduleTask(() -> {
            System.out.println("Task executed!");
        }, 5, TimeUnit.SECONDS, 1); // Priority of 1

        // Shutdown Gracefully
        scheduler.shutdown();
    }
}
```
This simple example schedules a one-time task with a 5-second delay. The task is executed based on priority.



## Usage
### Task Scheduling
You can schedule one-time tasks or recurring tasks with the scheduleTask, scheduleAtFixedRate, and scheduleWithFixedDelay methods.

#### One-time Task with Delay
```java
scheduler.scheduleTask(() -> {
    System.out.println("Execute this once after 3 seconds");
}, 3, TimeUnit.SECONDS, 1);  // Task with priority 1
```
- Parameters:
    - Task: The task to execute.
    - Delay: The time to wait before executing the task.
    - Time unit: The unit of time for the delay (e.g. `TimeUnit.SECONDS`).
    - Priority: Task's priority. Higher priority tasks will be executed before lower priority ones, regardless of their delay.
#### Recurring Task at Fixed Rate
```java
scheduler.scheduleAtFixedRate(() -> {
    System.out.println("Execute this every 5 seconds");
}, 2, 5, TimeUnit.SECONDS);
```
- Parameters:
    - Task: The task to execute.
    - Initial delay: The time to wait before the first execution
    - Period: The time between each execution.
    - Time unit: The unit of time for the delay and period (e.g. `TimeUnit.SECONDS`).
    - Priority: Task's priority. Higher priority tasks will be executed before lower priority ones, regardless of their delay.
#### Recurring Task with Fixed Delay
```java
scheduler.scheduleWithFixedDelay(() -> {
    System.out.println("Execute this with 3-second delay after each execution");
}, 2, 3, TimeUnit.SECONDS);
```
- Parameters:
  - Task: The task to execute.
  - Initial delay: The time to wait before the first execution
  - Delay: The delay between the end of the last execution and the start of the next.
  - Time unit: The unit of time for the delay and period (e.g. `TimeUnit.SECONDS`).
  - Priority: Task's priority. Higher priority tasks will be executed before lower priority ones, regardless of their delay.


## Task Prioritization
Tasks are scheduled based on their priority. Higher priority tasks are executed before lower-priority tasks. 
For example, a task with a priority of 10 will be executed before a task with a priority of 5.
```java
scheduler.scheduleTask(() -> {
System.out.println("High priority task");
}, 2, TimeUnit.SECONDS, 10); // Higher priority

scheduler.scheduleTask(() -> {
System.out.println("Low priority task");
}, 2, TimeUnit.SECONDS, 1);  // Lower priority
```



## Error Handling
Error handling is built into the framework. If any task throws an exception, it will be logged, and the scheduler will continue running.
```java
scheduler.scheduleTask(() -> {
    throw new RuntimeException("Intentional error");
}, 3, TimeUnit.SECONDS, 1);
```
If the task fails, you'll see:
```java 
Task execution failed: Intentional error
java.lang.RuntimeException: Intentional error
```



## Task Logging
Each task execution is logged with its priority and execution status:
- Start of execution: "Executing task with priority: X"
- Success: "Task completed successfully!"
- Failure: Detailed error logs.



## Shutdown
You can gracefully shut down the scheduler to stop new tasks from being submitted and allow running tasks to complete.
```java
scheduler.shutdown();
```

You can too add a shutdown hook to the JVM to automatically shut down the scheduler when the application is terminated.
```java
scheduler.registerShutdownHook();
```



## Examples

### Full Example with Prioritization and Recurring Tasks
```java
public class TaskDemo {
    public static void main(String[] args) {
        BackgroundTaskScheduler scheduler = new BackgroundTaskScheduler(3);

        // Schedule a high-priority one-time task
        scheduler.scheduleTask(() -> System.out.println("High priority task"), 5, TimeUnit.SECONDS, 10);

        // Schedule a low-priority one-time task
        scheduler.scheduleTask(() -> System.out.println("Low priority task"), 5, TimeUnit.SECONDS, 1);

        // Schedule a recurring task with fixed delay
        scheduler.scheduleWithFixedDelay(() -> System.out.println("Fixed delay task"), 1, 3, TimeUnit.SECONDS);

        // Schedule a recurring task with fixed rate
        scheduler.scheduleAtFixedRate(() -> System.out.println("Fixed rate task"), 1, 5, TimeUnit.SECONDS);

        // Gracefully shut down 
        scheduler.shutdown();
    }
}
```



## Contributing
We welcome contributions to improve. If you have any ideas or encounter issues, feel free to open a pull request or issue in our GitHub repository.
1. Fork the repository.
2. Create a new branch for your feature or bugfix.
3. Submit a pull request with a detailed description of the changes.



## License
This project is licensed under the MIT License. See the [LICENSE](https://opensource.org/license/mit) for details.