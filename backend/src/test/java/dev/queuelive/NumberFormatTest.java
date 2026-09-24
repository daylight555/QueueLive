package dev.queuelive;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class NumberFormatTest {
    @Test void numberingExpandsWithoutWrapping() {
        assertThat(QueueService.number(1)).isEqualTo("Q-001");
        assertThat(QueueService.number(1000)).isEqualTo("Q-1000");
    }
}
