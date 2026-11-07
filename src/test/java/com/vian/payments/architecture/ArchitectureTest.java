package com.vian.payments.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.vian.payments", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_tidak_boleh_bergantung_pada_framework =
            noClasses()
                    .that().resideInAPackage("..payment.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta..",
                            "..adapter..",
                            "..application.."
                    )
                    .because("domain harus pure Java dan bisa dites tanpa Spring");

    @ArchTest
    static final ArchRule application_tidak_boleh_import_adapter =
            noClasses()
                    .that().resideInAPackage("..payment.application..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter..")
                    .because("application hanya boleh bergantung pada port");

    @ArchTest
    static final ArchRule inbound_adapter_tidak_boleh_akses_outbound_adapter =
            noClasses()
                    .that().resideInAPackage("..adapter.in..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.out..")
                    .because("inbound adapter berkomunikasi lewat port/application service");
}
