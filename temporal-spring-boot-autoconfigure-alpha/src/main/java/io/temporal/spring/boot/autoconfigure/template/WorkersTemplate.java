/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.spring.boot.autoconfigure.template;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.metadata.POJOWorkflowImplMetadata;
import io.temporal.common.metadata.POJOWorkflowInterfaceMetadata;
import io.temporal.common.metadata.POJOWorkflowMethodMetadata;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.spring.boot.ActivityImpl;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.spring.boot.autoconfigure.properties.NamespaceProperties;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.properties.WorkerProperties;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.TypeAlreadyRegisteredException;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionValidationException;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.Assert;

/** Creates a {@link WorkerFactory} and Workers for a given namespace provided by WorkflowClient. */
public class WorkersTemplate implements BeanFactoryAware {
  private static final Logger log = LoggerFactory.getLogger(WorkersTemplate.class);

  private final @Nonnull TemporalProperties properties;
  private final @Nonnull NamespaceProperties namespaceProperties;
  private final @Nonnull WorkflowServiceStubs workflowServiceStubs;

  // if not null, we work with an environment with defined test server
  private final @Nullable TestWorkflowEnvironment testWorkflowEnvironment;

  private ConfigurableListableBeanFactory beanFactory;

  private WorkflowClient workflowClient;
  private WorkerFactory workerFactory;
  private Collection<Worker> workers;

  public WorkersTemplate(
      @Nonnull TemporalProperties properties,
      @Nonnull NamespaceProperties namespaceProperties,
      @Nonnull WorkflowServiceStubs workflowServiceStubs,
      @Nullable TestWorkflowEnvironment testWorkflowEnvironment) {
    this.properties = properties;
    this.namespaceProperties = namespaceProperties;
    this.workflowServiceStubs = workflowServiceStubs;
    this.testWorkflowEnvironment = testWorkflowEnvironment;
  }

  public WorkflowClient getWorkflowClient() {
    if (workflowClient == null) {
      this.workflowClient = createWorkflowClient();
    }
    return workflowClient;
  }

  private WorkflowClient createWorkflowClient() {
    if (testWorkflowEnvironment != null) {
      // TODO we should still respect the client properties here.
      // Instead of overriding, we should allow the test environment to configure the client.
      return testWorkflowEnvironment.getWorkflowClient();
    }

    WorkflowClientOptions.Builder clientOptionsBuilder = WorkflowClientOptions.newBuilder();

    if (namespaceProperties.getNamespace() != null) {
      clientOptionsBuilder.setNamespace(namespaceProperties.getNamespace());
    }

    return WorkflowClient.newInstance(
        workflowServiceStubs, clientOptionsBuilder.validateAndBuildWithDefaults());
  }

  public WorkerFactory getWorkerFactory() {
    if (workerFactory == null) {
      this.workerFactory = createWorkerFactory(getWorkflowClient());
    }
    return workerFactory;
  }

  public Collection<Worker> getWorkers() {
    if (workers == null) {
      this.workers = createWorkers(getWorkerFactory());
    }
    return workers;
  }

  WorkerFactory createWorkerFactory(WorkflowClient workflowClient) {
    if (testWorkflowEnvironment != null) {
      return testWorkflowEnvironment.getWorkerFactory();
    } else {
      return WorkerFactory.newInstance(workflowClient);
    }
  }

  private Collection<Worker> createWorkers(WorkerFactory workerFactory) {
    // explicitly configured workflow implementations
    Set<Worker> workers =
        properties.getWorkers() != null
            ? properties.getWorkers().stream()
                .map(
                    workerProperties ->
                        createWorkerFromAnExplicitConfig(workerFactory, workerProperties))
                .collect(Collectors.toSet())
            : new HashSet<>();

    if (properties.getWorkersAutoDiscovery() != null
        && properties.getWorkersAutoDiscovery().getPackages() != null) {
      Collection<Class<?>> autoDiscoveredWorkflowImplementationClasses =
          autoDiscoverWorkflowImplementations();
      for (Class<?> clazz : autoDiscoveredWorkflowImplementationClasses) {
        WorkflowImpl annotation = clazz.getAnnotation(WorkflowImpl.class);
        for (String taskQueue : annotation.taskQueues()) {
          Worker worker = workerFactory.tryGetWorker(taskQueue);
          if (worker == null) {
            log.info(
                "Creating a worker with default settings for a task queue '{}' "
                    + "caused by an auto-discovered workflow class {}",
                taskQueue,
                clazz);
            worker = workerFactory.newWorker(taskQueue);
            workers.add(worker);
          }

          try {
            configureWorkflowImplementation(worker, clazz);
            log.info(
                "Registering auto-discovered workflow class {} on a task queue '{}'",
                clazz,
                taskQueue);
          } catch (TypeAlreadyRegisteredException registeredEx) {
            log.info(
                "Skipping auto-discovered workflow class {} for task queue '{}' "
                    + "as workflow type '{}' is already registered on the worker",
                clazz,
                taskQueue,
                registeredEx.getRegisteredTypeName());
          }
        }
      }

      Map<String, Object> autoDiscoveredActivityBeans = autoDiscoverActivityBeans();
      autoDiscoveredActivityBeans.forEach(
          (beanName, bean) -> {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            ActivityImpl activityAnnotation =
                AnnotationUtils.findAnnotation(targetClass, ActivityImpl.class);
            if (activityAnnotation != null) {
              for (String taskQueue : activityAnnotation.taskQueues()) {
                Worker worker = workerFactory.tryGetWorker(taskQueue);
                if (worker == null) {
                  log.info(
                      "Creating a worker with default settings for a task queue '{}' "
                          + "caused by an auto-discovered activity class {}",
                      taskQueue,
                      targetClass);
                  worker = workerFactory.newWorker(taskQueue);
                  workers.add(worker);
                }

                try {
                  worker.registerActivitiesImplementations(bean);
                  log.info(
                      "Registering auto-discovered activity bean '{}' of class {} on task queue '{}'",
                      beanName,
                      targetClass,
                      taskQueue);
                } catch (TypeAlreadyRegisteredException registeredEx) {
                  log.info(
                      "Skipping auto-discovered activity bean '{}' for task queue '{}' "
                          + "as activity type '{}' is already registered on the worker",
                      beanName,
                      taskQueue,
                      registeredEx.getRegisteredTypeName());
                }
              }
            }
          });
    }
    return workers;
  }

  private Collection<Class<?>> autoDiscoverWorkflowImplementations() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(WorkflowImpl.class));
    Set<Class<?>> implementations = new HashSet<>();
    for (String pckg : properties.getWorkersAutoDiscovery().getPackages()) {
      Set<BeanDefinition> candidateComponents = scanner.findCandidateComponents(pckg);
      for (BeanDefinition beanDefinition : candidateComponents) {
        try {
          implementations.add(Class.forName(beanDefinition.getBeanClassName()));
        } catch (ClassNotFoundException e) {
          throw new BeanDefinitionValidationException(
              "Fail loading class for bean definition " + beanDefinition, e);
        }
      }
    }
    return implementations;
  }

  private Map<String, Object> autoDiscoverActivityBeans() {
    return beanFactory.getBeansWithAnnotation(ActivityImpl.class);
  }

  private Worker createWorkerFromAnExplicitConfig(
      WorkerFactory workerFactory, WorkerProperties workerProperties) {
    String taskQueue = workerProperties.getTaskQueue();
    if (workerFactory.tryGetWorker(taskQueue) != null) {
      throw new BeanDefinitionValidationException(
          "Worker for the task queue "
              + taskQueue
              + " already exists. Duplicate workers in the config?");
    }
    log.info("Creating configured worker for a task queue {}", taskQueue);
    Worker worker = workerFactory.newWorker(taskQueue);

    Collection<Class<?>> workflowClasses = workerProperties.getWorkflowClasses();
    if (workflowClasses != null) {
      workflowClasses.forEach(
          clazz -> {
            log.info(
                "Registering configured workflow class {} on a task queue '{}'", clazz, taskQueue);
            configureWorkflowImplementation(worker, clazz);
          });
    }

    Collection<String> activityBeans = workerProperties.getActivityBeans();
    if (activityBeans != null) {
      activityBeans.forEach(
          beanName -> {
            Object bean = beanFactory.getBean(beanName);
            log.info(
                "Registering configured activity bean '{}' of a {} class on task queue '{}'",
                beanName,
                AopUtils.getTargetClass(bean),
                taskQueue);
            worker.registerActivitiesImplementations(bean);
          });
    }

    return worker;
  }

  @SuppressWarnings("unchecked")
  private <T> void configureWorkflowImplementation(Worker worker, Class<?> clazz) {
    // TODO this code is a copy-past of a portion of
    // POJOWorkflowImplementationFactory#registerWorkflowImplementationType
    //  we should refactor it to separate the logic into reusable methods
    boolean hasWorkflowMethod = false;
    POJOWorkflowImplMetadata workflowMetadata = POJOWorkflowImplMetadata.newInstance(clazz);
    for (POJOWorkflowInterfaceMetadata workflowInterface :
        workflowMetadata.getWorkflowInterfaces()) {
      Optional<POJOWorkflowMethodMetadata> workflowMethod = workflowInterface.getWorkflowMethod();
      if (workflowMethod.isPresent()) {
        // TODO this is ugly. POJOWorkflowMethodMetadata needs to be generified
        worker.addWorkflowImplementationFactory(
            (Class<T>) workflowInterface.getInterfaceClass(),
            () -> (T) beanFactory.createBean(clazz));
        hasWorkflowMethod = true;
        break;
      }
    }

    if (!hasWorkflowMethod) {
      throw new BeanDefinitionValidationException(clazz + " doesn't have workflowMethod");
    }
  }

  @Override
  public void setBeanFactory(@Nonnull BeanFactory beanFactory) throws BeansException {
    Assert.isInstanceOf(ConfigurableListableBeanFactory.class, beanFactory);
    this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
  }
}