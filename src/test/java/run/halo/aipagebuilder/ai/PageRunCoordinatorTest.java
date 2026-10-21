package run.halo.aipagebuilder.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class PageRunCoordinatorTest {
    @Test
    void restartingClearsTheStoppingLatch() {
        var coordinator = new PageRunCoordinator();
        assertThat(coordinator.stopping()).isFalse();

        coordinator.stop((name, run) -> Mono.empty());
        assertThat(coordinator.stopping()).isTrue();

        coordinator.start();
        assertThat(coordinator.stopping()).isFalse();
    }
}
