package io.github.voraes.jscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowTest {
    @Test
    void validatesAndExposesImmutableGraph() {
        Workflow workflow = Workflow.builder("report")
                .job("orders", () -> { })
                .job("customers", () -> { })
                .job("render", () -> { })
                .dependsOn("render", "orders", "customers")
                .failurePolicy(WorkflowFailurePolicy.SKIP_DEPENDENTS)
                .build();

        assertEquals(List.of("orders", "customers", "render"),
                workflow.nodes().stream().toList());
        assertEquals(List.of("orders", "customers"),
                workflow.dependenciesOf("render").stream().toList());
        assertEquals(WorkflowFailurePolicy.SKIP_DEPENDENTS, workflow.failurePolicy());
        assertThrows(UnsupportedOperationException.class, () -> workflow.nodes().remove("orders"));
    }

    @Test
    void reportsUsefulCyclePath() {
        WorkflowCycleException exception = assertThrows(WorkflowCycleException.class,
                () -> Workflow.builder("cycle")
                        .job("a", () -> { })
                        .job("b", () -> { })
                        .job("c", () -> { })
                        .dependsOn("a", "b")
                        .dependsOn("b", "c")
                        .dependsOn("c", "a")
                        .build());

        assertEquals(List.of("a", "b", "c", "a"), exception.cycle());
        assertTrue(exception.getMessage().contains("a -> b -> c -> a"));
    }

    @Test
    void rejectsUnknownAndDuplicateNodes() {
        assertThrows(IllegalArgumentException.class, () -> Workflow.builder("duplicate")
                .job("node", () -> { })
                .job("node", () -> { }));
        assertThrows(IllegalStateException.class, () -> Workflow.builder("unknown")
                .job("node", () -> { })
                .dependsOn("node", "missing")
                .build());
        assertThrows(IllegalStateException.class, () -> Workflow.builder("empty").build());
    }

    @Test
    void exportsDeterministicEscapedDot() {
        Workflow workflow = Workflow.builder("daily \"report\"")
                .job("fetch\\orders", () -> { })
                .job("render", () -> { })
                .dependsOn("render", "fetch\\orders")
                .build();

        assertEquals("""
                digraph "daily \\"report\\"" {
                  "fetch\\\\orders";
                  "render";
                  "fetch\\\\orders" -> "render";
                }
                """, workflow.toDot());
        assertFalse(workflow.toDot().contains("null"));
    }
}
