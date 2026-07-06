package com.ichi2.anki.practice

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test

class TopicDisplayTest {
    @Test
    fun formatTopicLabel_titleCasesTopicTagsForDisplay() {
        assertThat(formatTopicLabel("general chemistry"), equalTo("General Chemistry"))
        assertThat(formatTopicLabel("GENERAL_CHEMISTRY"), equalTo("General Chemistry"))
        assertThat(formatTopicLabel("kinetics"), equalTo("Kinetics"))
        assertThat(formatTopicLabel("acids-and-bases"), equalTo("Acids And Bases"))
        assertThat(formatTopicLabel("DNA"), equalTo("DNA"))
        assertThat(formatTopicLabel("dna replication"), equalTo("DNA Replication"))
        assertThat(formatTopicLabel("General Chemistry"), equalTo("General Chemistry"))
        assertThat(formatTopicLabel("psychology"), equalTo("Psychology"))
        assertThat(formatTopicLabel("PSYCHOLOGY"), equalTo("Psychology"))
        assertThat(formatTopicLabel("pSYCHOLOGY"), equalTo("Psychology"))
        assertThat(formatTopicLabel("pHYSICS"), equalTo("Physics"))
        assertThat(
            formatTopicLabel("development & social psychology"),
            equalTo("Development & Social Psychology"),
        )
        assertThat(formatTopicLabel("ph"), equalTo("pH"))
        assertThat(formatTopicLabel("pka"), equalTo("pKa"))
    }
}
