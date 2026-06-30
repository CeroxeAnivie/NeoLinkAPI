import java.time.Duration
import java.time.temporal.ChronoUnit

plugins {
    id("com.gradleup.nmcp.settings") version "1.5.0"
}

rootProject.name = "NeoLinkAPI"

include("shared", "desktop")

nmcpSettings {
    centralPortal {
        username = providers.gradleProperty("centralUsername").getOrElse("")
        password = providers.gradleProperty("centralPassword").getOrElse("")
        publishingType = "AUTOMATIC"
        publicationName = "NeoLinkAPI-7.2.4"
        validationTimeout = Duration.of(30, ChronoUnit.MINUTES)
    }
}
