package io.github.voraes.jscheduler.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a zero-argument void bean method as scheduler-managed work. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ScheduledJob {
    /** Logical job name; defaults to {@code beanName.methodName}. */
    String name() default "";

    /** Delay before the first occurrence, as a suffix duration or ISO-8601 duration. */
    String initialDelay() default "0s";

    /** Planned cadence; mutually exclusive with {@link #fixedDelay()}. */
    String fixedRate() default "";

    /** Delay after completion; mutually exclusive with {@link #fixedRate()}. */
    String fixedDelay() default "";

    /** Ready-queue priority; larger values run first after eligibility. */
    int priority() default 0;
}
