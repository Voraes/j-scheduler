/**
 * Thread-safe embedded task scheduling, resilient execution, and dependency workflows.
 *
 * <p>Public configuration objects and execution snapshots are immutable. {@link
 * io.github.voraes.jscheduler.Scheduler Scheduler}, {@link
 * io.github.voraes.jscheduler.JobHandle JobHandle}, and {@link
 * io.github.voraes.jscheduler.WorkflowHandle WorkflowHandle} support concurrent callers. Timeout,
 * cancellation, replacement, and immediate shutdown use cooperative thread interruption; Java code
 * that ignores interruption cannot be forcibly terminated safely.
 */
package io.github.voraes.jscheduler;
