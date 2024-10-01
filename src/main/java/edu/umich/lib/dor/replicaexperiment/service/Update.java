package edu.umich.lib.dor.replicaexperiment.service;

import java.nio.file.Path;

import edu.umich.lib.dor.replicaexperiment.domain.Curator;
import edu.umich.lib.dor.replicaexperiment.domain.InfoPackage;
import edu.umich.lib.dor.replicaexperiment.domain.Replica;
import edu.umich.lib.dor.replicaexperiment.domain.Repository;
import edu.umich.lib.dor.replicaexperiment.exception.NoEntityException;

public class Update implements Command {
    InfoPackageService infoPackageService;
    RepositoryService repositoryService;
    ReplicaService replicaService;
    RepositoryClientRegistry repositoryClientRegistry;
    DepositDirectory depositDir;
    Curator curator;
    String packageIdentifier;
    Path sourcePath;
    String repositoryName;
    String message;

    InfoPackage existingPackage;
    Repository repository;
    RepositoryClient repositoryClient;
    Package sourcePackage;
    Replica replica;

    public Update(
        InfoPackageService infoPackageService,
        RepositoryService repositoryService,
        ReplicaService replicaService,
        RepositoryClientRegistry repositoryClientRegistry,
        DepositDirectory depositDir,
        Curator curator,
        String packageIdentifier,
        Path sourcePath,
        String repositoryName,
        String message
    ) {
        this.infoPackageService = infoPackageService;
        this.repositoryService = repositoryService;
        this.replicaService = replicaService;
        this.repositoryClientRegistry = repositoryClientRegistry;
        this.depositDir = depositDir;
        this.curator = curator;
        this.packageIdentifier = packageIdentifier;
        this.sourcePath = sourcePath;
        this.repositoryName = repositoryName;
        this.message = message;

        this.existingPackage = infoPackageService.getInfoPackage(packageIdentifier);
        if (existingPackage == null) {
            throw new NoEntityException(
                String.format(
                    "No package with identifier \"%s\" was found.",
                    packageIdentifier
                )
            );
        }

        this.repository = repositoryService.getRepository(repositoryName);
        if (repository == null) {
            throw new NoEntityException(
                String.format(
                    "No repository with name \"%s\" was found.",
                    repositoryName
                )
            );
        }
        this.repositoryClient = repositoryClientRegistry.getClient(repositoryName);
        this.replica = replicaService.getReplica(existingPackage, repository);
        if (replica == null) {
            throw new NoEntityException(
                String.format(
                    "No replica for package \"%s\" was found in repository \"%s\".",
                    packageIdentifier,
                    repositoryName
                )
            );
        }

        this.sourcePackage = depositDir.getPackage(sourcePath);
    }

    public void execute() {
        repositoryClient.updateObjectFiles(
            packageIdentifier,
            sourcePackage,
            curator,
            message
        );
        replicaService.updateReplica(replica);
    }
}
