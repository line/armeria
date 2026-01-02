import com.linecorp.armeria.conventions.spring.SpringBuildUtil

dependencies {
    api(libs.spring6.webflux)
    testImplementation(libs.spring6.context)
    testImplementation(libs.spring6.test)
}
val spring7 = "spring7"
SpringBuildUtil.useMainSources(project(":spring:$spring7"), project)
SpringBuildUtil.useTestSources(project(":spring:$spring7"), project)
