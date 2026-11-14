package com.loofie.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import com.loofie.core.job.domain.Job;
import com.loofie.core.job.domain.JobStatus;

@AnalyzeClasses(packages = "com.loofie.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** Single entry point: only the transition engine changes job state. */
    @ArchTest
    static final ArchRule onlyTheEngineMovesJobs = noClasses()
            .that().doNotHaveFullyQualifiedName("com.loofie.core.job.app.JobTransitionService")
            .and().doNotHaveFullyQualifiedName(Job.class.getName())
            .should().callMethod(Job.class, "moveTo", JobStatus.class, java.time.Instant.class)
            .because("all job state changes go through JobTransitionService.transition()");

    /** The domain must not know about web, security, Kafka or the database layer. */
    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.security..", "org.springframework.kafka..",
                    "org.springframework.jdbc..", "..infra..", "..api..")
            .because("domain code takes an explicit Actor and stays independent of adapters");

    /** Nobody outside the events package writes job_events. */
    @ArchTest
    static final ArchRule eventLogOnlyViaRecorder = noClasses()
            .that().resideOutsideOfPackages("..events..", "..job.app..")
            .should().dependOnClassesThat().haveSimpleName("JobEventLog")
            .because("job_events is written only alongside a state change");

    /** Feature packages talk through app services, never each other's repositories. */
    @ArchTest
    static final ArchRule noCrossFeatureRepositoryAccess = noClasses()
            .that().resideInAPackage("com.loofie.core.order..")
            .should().dependOnClassesThat().resideInAPackage("com.loofie.core.job.infra..")
            .because("other features use JobTransitionService / JobIntakeService, not job repositories");
}
