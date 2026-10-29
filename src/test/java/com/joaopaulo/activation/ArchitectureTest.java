package com.joaopaulo.activation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.joaopaulo.activation", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsIndependent = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..api..", "..application..", "..provider..", "..config..");

    @ArchTest
    static final ArchRule innerLayersDoNotDependOnApi = noClasses()
            .that().resideInAnyPackage("..application..", "..provider..")
            .should().dependOnClassesThat().resideInAPackage("..api..");

    @ArchTest
    static final ArchRule providerDoesNotDependOnApplication = noClasses()
            .that().resideInAPackage("..provider..")
            .should().dependOnClassesThat().resideInAPackage("..application..");
}
