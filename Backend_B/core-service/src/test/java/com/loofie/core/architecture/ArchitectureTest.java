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

    /** The domain must not know about web, security, HTTP clients or the database layer. */
    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.security..", "java.net.http..",
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
            .that().resideOutsideOfPackage("com.loofie.core.job..")
            .should().dependOnClassesThat().resideInAPackage("com.loofie.core.job.infra..")
            .because("other features use the job app services (JobTransitionService, JobQueryService), "
                    + "never job repositories or the jobs table");

    @ArchTest
    static final ArchRule noCrossFeatureCalendarRepositoryAccess = noClasses()
            .that().resideOutsideOfPackage("com.loofie.core.calendar..")
            .should().dependOnClassesThat().resideInAPackage("com.loofie.core.calendar.infra..")
            .because("other features use HolidayCalendar / HolidayAdminService");

    /** F1: identity keeps its tables to itself so it can become a separate Identity Service later. */
    @ArchTest
    static final ArchRule noCrossFeatureIdentityRepositoryAccess = noClasses()
            .that().resideOutsideOfPackage("com.loofie.core.identity..")
            .and().resideOutsideOfPackage("com.loofie.core.housekeeping..")
            .should().dependOnClassesThat().resideInAPackage("com.loofie.core.identity.infra..")
            .because("other features see a user only through ActorResolver / SessionAuthenticator");

    /** F1 round C: the passport is read through CreatorDirectory / CreatorProfileService, never its tables. */
    @ArchTest
    static final ArchRule noCrossFeatureCreatorRepositoryAccess = noClasses()
            .that().resideOutsideOfPackage("com.loofie.core.creator..")
            .and().resideOutsideOfPackage("com.loofie.core.housekeeping..")
            .should().dependOnClassesThat().resideInAPackage("com.loofie.core.creator.infra..")
            .because("other features ask CreatorDirectory (the job engine via the CreatorEligibility port)");

    /** F7: videos, briefs and reviews are reached through the draft app services or the DraftGate port. */
    @ArchTest
    static final ArchRule noCrossFeatureDraftRepositoryAccess = noClasses()
            .that().resideOutsideOfPackage("com.loofie.core.draft..")
            .should().dependOnClassesThat().resideInAPackage("com.loofie.core.draft.infra..")
            .because("the job engine asks DraftGate; other features use the draft app services");

    /** Layers point one way: api -> app -> domain, and infra serves app. Never backwards. */
    @ArchTest
    static final ArchRule infraDoesNotDependOnUpperLayers = noClasses()
            .that().resideInAPackage("..infra..")
            .should().dependOnClassesThat().resideInAnyPackage("..app..", "..api..", "..timer..")
            .because("the database layer serves the application layer, not the other way round");

    @ArchTest
    static final ArchRule apiDoesNotReachTheDatabase = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAnyPackage("..infra..", "org.springframework.jdbc..")
            .because("controllers only map HTTP; use cases and permission checks live in app services");

    /** Raw SQL lives in the data layers only, so a renamed column breaks in one known place. */
    @ArchTest
    static final ArchRule sqlOnlyInDataLayers = noClasses()
            .that().resideInAnyPackage("..app..", "..api..", "..domain..", "..timer..", "..ops..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")
            .because("SQL belongs in infra repositories (or the messaging/events/common plumbing)");
}
