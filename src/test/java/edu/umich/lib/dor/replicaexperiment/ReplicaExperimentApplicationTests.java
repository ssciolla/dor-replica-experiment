package edu.umich.lib.dor.replicaexperiment;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.util.FileSystemUtils;

import edu.umich.lib.dor.replicaexperiment.domain.Curator;
import edu.umich.lib.dor.replicaexperiment.domain.InfoPackage;
import edu.umich.lib.dor.replicaexperiment.domain.Replica;
import edu.umich.lib.dor.replicaexperiment.domain.Repository;
import edu.umich.lib.dor.replicaexperiment.service.ChecksumCalculator;
import edu.umich.lib.dor.replicaexperiment.service.Deposit;
import edu.umich.lib.dor.replicaexperiment.service.DepositDirectory;
import edu.umich.lib.dor.replicaexperiment.service.DepositFactory;
import edu.umich.lib.dor.replicaexperiment.service.InfoPackageService;
import edu.umich.lib.dor.replicaexperiment.service.OcflFilesystemRepositoryClient;
import edu.umich.lib.dor.replicaexperiment.service.Package;
import edu.umich.lib.dor.replicaexperiment.service.Purge;
import edu.umich.lib.dor.replicaexperiment.service.PurgeFactory;
import edu.umich.lib.dor.replicaexperiment.service.ReplicaService;
import edu.umich.lib.dor.replicaexperiment.service.ReplicationFactory;
import edu.umich.lib.dor.replicaexperiment.service.RepositoryClient;
import edu.umich.lib.dor.replicaexperiment.service.RepositoryClientRegistry;
import edu.umich.lib.dor.replicaexperiment.service.RepositoryService;
import edu.umich.lib.dor.replicaexperiment.service.Update;
import edu.umich.lib.dor.replicaexperiment.service.UpdateFactory;

@DataJpaTest
@ContextConfiguration(classes = {TestsConfig.class, TestConfiguration.class})
@ComponentScan(basePackages = {"edu.umich.lib.dor.replicaexperiment.service"})
@EntityScan(basePackages = {"edu.umich.lib.dor.replicaexperiment.domain"})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class ReplicaExperimentApplicationTests {
    static final Logger log = LoggerFactory.getLogger(ReplicaExperimentApplication.class);

    Path testReposPath = Paths.get("src", "test", "resources", "test_repositories");
    String repoOneName = "repo_one";
    Path repoOnePath = testReposPath.resolve("repo_one");
    Path repoOneStoragePath = repoOnePath.resolve("storage");
    Path repoOneWorkspacePath = repoOnePath.resolve("workspace");

    String repoTwoName = "repo_two";
    Path repoTwoPath = testReposPath.resolve("repo_two");
    Path repoTwoStoragePath = repoTwoPath.resolve("storage");
    Path repoTwoWorkspacePath = repoTwoPath.resolve("workspace");

    Path depositPath = testReposPath.resolve("deposit");
    DepositDirectory depositDir = new DepositDirectory(depositPath);

    String depositAIdentifier = "A";
    Path depositAPath = Paths.get("A");
    Path updateAPath = Paths.get("Update_A");

    Path stagingPath = testReposPath.resolve("staging");

    Curator testCurator = new Curator("test", "test@example.edu");

    @Autowired
    InfoPackageService infoPackageService;

    @Autowired
    RepositoryService repositoryService;

    @Autowired
    ReplicaService replicaService;

    DepositFactory depositFactory;
    ReplicationFactory replicationFactory;
    UpdateFactory updateFactory;
    PurgeFactory purgeFactory;

    RepositoryClient repoOneClient;
    RepositoryClient repoTwoClient;

    void resetDirPath(Path path) throws IOException {
        if (Files.exists(path)) {
            FileSystemUtils.deleteRecursively(path);
        }
        Files.createDirectories(path);
    }

    @BeforeEach
    void init() throws IOException {
        resetDirPath(repoOneStoragePath);
        resetDirPath(repoOneWorkspacePath);
        resetDirPath(repoTwoStoragePath);
        resetDirPath(repoTwoWorkspacePath);

        this.repoOneClient = new OcflFilesystemRepositoryClient(
            repoOneStoragePath, repoOneWorkspacePath
        );
        this.repoTwoClient = new OcflFilesystemRepositoryClient(
            repoTwoStoragePath, repoTwoWorkspacePath
        );

        RepositoryClientRegistry registry = new RepositoryClientRegistry();
        registry.register(repoOneName, repoOneClient);
        registry.register(repoTwoName, repoTwoClient);
        for (String repositoryName : registry.listClients()) {
            this.repositoryService.getOrCreateRepository(repositoryName);
        }

        this.depositFactory = new DepositFactory(
            infoPackageService, repositoryService, replicaService, registry, depositDir
        );
        this.replicationFactory = new ReplicationFactory(
            infoPackageService, repositoryService, replicaService, registry, stagingPath
        );
        this.updateFactory = new UpdateFactory(
            infoPackageService, repositoryService, replicaService, registry, depositDir
        );
        this.purgeFactory = new PurgeFactory(
            infoPackageService, repositoryService, replicaService, registry
        );

    }

    @Test
    void contextLoads() {
    }

    @Test
    void depositCreatesFilesInARepository() {
        var deposit = depositFactory.create(
            testCurator, depositAIdentifier, depositAPath, repoOneName, "first version!!!"
        );
        deposit.execute();

        InfoPackage infoPackageA = infoPackageService.getInfoPackage(depositAIdentifier);
        Repository repository = repositoryService.getRepository(repoOneName);

        assertEquals("A", infoPackageA.getIdentifier());

        assertEquals("FILE_SYSTEM", repository.getType());
        assertEquals(repoOneName, repository.getName());

        assertEquals(1, infoPackageA.getReplicas().size());
        assertTrue(infoPackageA.hasAReplicaIn(repoOneName));

        List<Path> filePaths = repoOneClient.getStorageFilePaths(depositAIdentifier);
        for (Path filePath : filePaths) {
            Path fullPath = repoOneStoragePath.resolve(filePath);
            assertTrue(Files.exists(fullPath));
        }
    }

    @Test
    void replicationCopiesFilesToAnotherRepository() {
        Deposit deposit = depositFactory.create(
            testCurator, depositAIdentifier, depositAPath, repoOneName, "first version!!!"
        );
        deposit.execute();
        var replication = replicationFactory.create(depositAIdentifier, repoOneName, repoTwoName);
        replication.execute();

        InfoPackage infoPackage = infoPackageService.getInfoPackage(depositAIdentifier);
        assertEquals(2, infoPackage.getNumReplicas());
        Repository repositoryTwo = repositoryService.getRepository(repoTwoName);
        var replicasInRepoTwo = repositoryTwo.getReplicas();
        assertEquals(1, replicasInRepoTwo.size());
        if (replicasInRepoTwo.size() == 1) {
            Replica repoTwoReplica = replicasInRepoTwo.iterator().next();
            assertEquals(depositAIdentifier, repoTwoReplica.getInfoPackage().getIdentifier());
        }

        List<Path> filePaths = repoTwoClient.getStorageFilePaths(depositAIdentifier);
        for (Path filePath : filePaths) {
            Path fullPath = repoTwoStoragePath.resolve(filePath);
            assertTrue(Files.exists(fullPath));
        }
    }

    @Test
    void updateModifiesFilesInRepository() {
        Deposit deposit = depositFactory.create(
            testCurator, depositAIdentifier, depositAPath, repoOneName, "first version!!!"
        );
        deposit.execute();

        Update update = updateFactory.create(
            testCurator, depositAIdentifier, updateAPath, repoOneName, "second version!!!"
        );
        update.execute();

        Package updateAPackage = depositDir.getPackage(updateAPath);
        List<Path> updateFilePaths = updateAPackage.getFilePaths();
        List<Path> packageFilePaths = repoOneClient.getFilePaths(depositAIdentifier);
        assertTrue(Set.copyOf(packageFilePaths).containsAll(Set.copyOf(updateFilePaths)));

        List<Path> storageFilePaths = repoOneClient.getStorageFilePaths(depositAIdentifier);
        for (Path storageFilePath : storageFilePaths) {
            Path fullPath = repoOneStoragePath.resolve(storageFilePath);
            assertTrue(Files.exists(fullPath));
        }

        for (Path updateFilePath : updateFilePaths) {
            Path fullUpdateFilePath = updateAPackage.getRootPath().resolve(updateFilePath);
            String updateFileChecksum = ChecksumCalculator.calculate(fullUpdateFilePath);
            Optional<Path> maybeStoragePath = storageFilePaths.stream()
                .filter(p -> p.endsWith(updateFilePath))
                .findFirst();
            assertTrue(maybeStoragePath.isPresent());
            if (maybeStoragePath.isPresent()) {
                Path fullMatchingStoragePath = repoOneStoragePath.resolve(maybeStoragePath.get());
                String storageFileChecksum = ChecksumCalculator.calculate(fullMatchingStoragePath);
                assertEquals(updateFileChecksum, storageFileChecksum);
            }
        }
    }

    @Test
    void purgeRemovesPackageFromRepository() {
        Deposit deposit = depositFactory.create(
            testCurator, depositAIdentifier, depositAPath, repoOneName, "first version!!!"
        );
        deposit.execute();

        List<Path> filePaths = repoOneClient.getStorageFilePaths(depositAIdentifier);
        final InfoPackage infoPackage = infoPackageService.getInfoPackage(depositAIdentifier);
        final Repository repository = repositoryService.getRepository(repoOneName);

        Purge purge = purgeFactory.create(depositAIdentifier, repoOneName);
        purge.execute();

        assertFalse(repoOneClient.hasObject(depositAIdentifier));

        for (Path filePath : filePaths) {
            Path fullPath = repoOneStoragePath.resolve(filePath);
            assertFalse(Files.exists(fullPath));
        }

        assertNull(replicaService.getReplica(infoPackage, repository));
        assertNotNull(repositoryService.getRepository(repoOneName));
    }
}
