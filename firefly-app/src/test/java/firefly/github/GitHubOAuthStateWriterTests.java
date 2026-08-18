package firefly.github;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import firefly.github.dao.GitHubOAuthStateRepository;
import firefly.github.model.GitHubOAuthStateEntity;
import firefly.github.service.GitHubOAuthStateWriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class GitHubOAuthStateWriterTests {

    @Mock
    private GitHubOAuthStateRepository stateRepository;

    @Test
    void returnsStateOnlyWhenConditionalDeleteWins() {
        GitHubOAuthStateEntity pending = new GitHubOAuthStateEntity().setId(7L).setState("state");
        when(stateRepository.findByState("state")).thenReturn(Optional.of(pending));
        when(stateRepository.consumePending(7L)).thenReturn(0);

        Optional<GitHubOAuthStateEntity> result =
            new GitHubOAuthStateWriter(stateRepository).take("state");

        assertTrue(result.isEmpty());
        verify(stateRepository).consumePending(7L);
    }

    @Test
    void doesNotDeleteWhenStateDoesNotExist() {
        when(stateRepository.findByState("missing")).thenReturn(Optional.empty());

        Optional<GitHubOAuthStateEntity> result =
            new GitHubOAuthStateWriter(stateRepository).take("missing");

        assertTrue(result.isEmpty());
        verify(stateRepository, never()).consumePending(org.mockito.ArgumentMatchers.anyLong());
    }
}
