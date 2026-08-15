package io.github.voraes.jscheduler.spring;

import io.github.voraes.jscheduler.Job;
import io.github.voraes.jscheduler.JobHandle;
import io.github.voraes.jscheduler.Schedule;
import io.github.voraes.jscheduler.Scheduler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

final class ScheduledJobBeanPostProcessor implements DestructionAwareBeanPostProcessor {
    private final ObjectProvider<Scheduler> schedulers;
    private final Map<String, List<JobHandle>> handles = new ConcurrentHashMap<>();

    ScheduledJobBeanPostProcessor(ObjectProvider<Scheduler> schedulers) {
        this.schedulers = schedulers;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        Map<Method, ScheduledJob> methods = MethodIntrospector.selectMethods(targetClass,
                (MethodIntrospector.MetadataLookup<ScheduledJob>) method ->
                        AnnotatedElementUtils.findMergedAnnotation(method, ScheduledJob.class));
        if (methods.isEmpty()) {
            return bean;
        }
        Scheduler scheduler = schedulers.getObject();
        List<JobHandle> beanHandles = new ArrayList<>();
        for (Map.Entry<Method, ScheduledJob> entry : methods.entrySet()) {
            Method method = AopUtils.selectInvocableMethod(entry.getKey(), bean.getClass());
            ScheduledJob annotation = entry.getValue();
            validate(method, annotation, targetClass);
            ReflectionUtils.makeAccessible(method);
            String jobName = annotation.name().isBlank()
                    ? beanName + "." + method.getName() : annotation.name();
            Job job = Job.builder(jobName)
                    .task(() -> ReflectionUtils.invokeMethod(method, bean))
                    .priority(annotation.priority())
                    .build();
            beanHandles.add(scheduler.schedule(job, schedule(annotation)));
        }
        handles.put(beanName, List.copyOf(beanHandles));
        return bean;
    }

    @Override
    public void postProcessBeforeDestruction(Object bean, String beanName) {
        List<JobHandle> beanHandles = handles.remove(beanName);
        if (beanHandles != null) {
            beanHandles.forEach(handle -> handle.cancel(true));
        }
    }

    @Override
    public boolean requiresDestruction(Object bean) {
        return true;
    }

    private static void validate(Method method, ScheduledJob annotation, Class<?> targetClass) {
        if (method.getParameterCount() != 0 || method.getReturnType() != Void.TYPE
                || Modifier.isStatic(method.getModifiers())) {
            throw new IllegalStateException("@ScheduledJob method must be a non-static, zero-argument"
                    + " void method: " + targetClass.getName() + "." + method.getName());
        }
        if (!annotation.fixedRate().isBlank() && !annotation.fixedDelay().isBlank()) {
            throw new IllegalStateException("@ScheduledJob cannot define both fixedRate and fixedDelay: "
                    + targetClass.getName() + "." + method.getName());
        }
    }

    private static Schedule schedule(ScheduledJob annotation) {
        Duration initialDelay = SchedulerDurationParser.parse(annotation.initialDelay(),
                "initialDelay");
        if (!annotation.fixedRate().isBlank()) {
            return Schedule.fixedRate(initialDelay,
                    SchedulerDurationParser.parse(annotation.fixedRate(), "fixedRate"));
        }
        if (!annotation.fixedDelay().isBlank()) {
            return Schedule.fixedDelay(initialDelay,
                    SchedulerDurationParser.parse(annotation.fixedDelay(), "fixedDelay"));
        }
        return Schedule.delayed(initialDelay);
    }
}
