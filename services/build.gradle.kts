// 루트 빌드 — 공통 설정.
// 각 서비스 모듈은 자신의 build.gradle.kts에서 Kotlin 혹은 Java 여부만 결정.

plugins {
    // Spring Boot / 의존성 관리 플러그인은 모듈에서 apply. 여기선 버전만 선언.
    id("org.springframework.boot") version "3.3.0" apply false
    id("io.spring.dependency-management") version "1.1.5" apply false
    kotlin("jvm") version "1.9.24" apply false
    kotlin("plugin.spring") version "1.9.24" apply false
}

allprojects {
    group = "com.kakao.search"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    // 자바/코틀린 공통 — 17 타겟.
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
        // Spring이 @Qualifier 없는 생성자 파라미터를 bean name으로 매칭할 수 있도록.
        // 없으면 같은 타입 bean이 2개일 때 NoUniqueBeanDefinitionException.
        options.compilerArgs.add("-parameters")
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = listOf("-Xjsr305=strict")
        }
    }
}
