/*
 * Copyright (C) 2020 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.nessie.server.providers;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dremio.nessie.backend.Backend;
import com.dremio.nessie.model.CommitMeta;
import com.dremio.nessie.model.Contents;
import com.dremio.nessie.server.config.ApplicationConfig;
import com.dremio.nessie.server.config.converters.VersionStoreType;
import com.dremio.nessie.services.config.ServerConfig;
import com.dremio.nessie.versioned.BranchName;
import com.dremio.nessie.versioned.ReferenceAlreadyExistsException;
import com.dremio.nessie.versioned.ReferenceNotFoundException;
import com.dremio.nessie.versioned.StoreWorker;
import com.dremio.nessie.versioned.VersionStore;
import com.dremio.nessie.versioned.impl.DynamoStore;
import com.dremio.nessie.versioned.impl.DynamoStoreConfig;
import com.dremio.nessie.versioned.impl.DynamoVersionStore;
import com.dremio.nessie.versioned.impl.JGitVersionStore;
import com.dremio.nessie.versioned.impl.experimental.NessieRepository;
import com.dremio.nessie.versioned.memory.InMemoryVersionStore;

import software.amazon.awssdk.regions.Region;

@Singleton
public class VersionStoreFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(VersionStoreFactory.class);

  private final ApplicationConfig config;

  @Inject
  public VersionStoreFactory(ApplicationConfig config) {
    this.config = config;
  }

  @ConfigProperty(name = "quarkus.dynamodb.aws.region")
  String region;
  @ConfigProperty(name = "quarkus.dynamodb.endpoint-override")
  Optional<String> endpoint;


  @Produces
  public StoreWorker<Contents, CommitMeta> worker() {
    return new TableCommitMetaStoreWorker();
  }

  /**
   * default config for lambda function.
   */
  @Produces
  @Singleton
  public VersionStore<Contents, CommitMeta> configuration(
      TableCommitMetaStoreWorker storeWorker, Repository repository, ServerConfig config) {
    VersionStore<Contents, CommitMeta> store = getVersionStore(storeWorker, repository);
    if (!store.getNamedRefs().findFirst().isPresent()) {
      // if this is a new database, create a branch with the default branch name.
      try {
        store.create(BranchName.of(config.getDefaultBranch()), Optional.empty());
      } catch (ReferenceNotFoundException | ReferenceAlreadyExistsException e) {
        LOGGER.warn("Failed to create default branch of {}.", config.getDefaultBranch(), e);
      }
    }

    return store;
  }

  private VersionStore<Contents, CommitMeta> getVersionStore(TableCommitMetaStoreWorker storeWorker, Repository repository) {
    switch (config.getVersionStoreConfig().getVersionStoreType()) {
      case DYNAMO:
        LOGGER.info("Using Dyanmo Version store");
        return new DynamoVersionStore<>(storeWorker, dyanamo(), false);
      case JGIT:
        LOGGER.info("Using JGit Version Store");
        return new JGitVersionStore<>(repository, storeWorker);
      case INMEMORY:
        LOGGER.info("Using In Memory version store");
        return InMemoryVersionStore.<Contents, CommitMeta>builder()
            .metadataSerializer(storeWorker.getMetadataSerializer())
            .valueSerializer(storeWorker.getValueSerializer())
            .build();
      default:
        throw new RuntimeException(String.format("unknown jgit repo type %s", config.getVersionStoreConfig().getVersionStoreType()));
    }
  }

  /**
   * create a dynamo store based on config.
   */
  private DynamoStore dyanamo() {
    if (!config.getVersionStoreConfig().getVersionStoreType().equals(VersionStoreType.DYNAMO)) {
      return null;
    }

    DynamoStore dynamo = new DynamoStore(DynamoStoreConfig.builder()
                                            .endpoint(endpoint.map(e -> {
                                              try {
                                                return new URI(e);
                                              } catch (URISyntaxException uriSyntaxException) {
                                                throw new RuntimeException(uriSyntaxException);
                                              }
                                            }))
                                            .region(Region.of(region))
                                            .initializeDatabase(config.getVersionStoreDynamoConfig().isDynamoInitialize())
                                            .refTableName(config.getVersionStoreDynamoConfig().getRefTableName())
                                            .treeTableName(config.getVersionStoreDynamoConfig().getTreeTableName())
                                            .valueTableName(config.getVersionStoreDynamoConfig().getValueTableName())
                                            .build());
    dynamo.start();
    return dynamo;
  }

  /**
   * produce a git repo based on config.
   */
  @Produces
  public Repository repository(Backend backend) throws IOException, GitAPIException {
    if (!config.getVersionStoreConfig().getVersionStoreType().equals(VersionStoreType.JGIT)) {
      return null;
    }
    switch (config.getVersionStoreJGitConfig().getJgitStoreType()) {
      case DYNAMO:
        LOGGER.info("JGit Version store has been configured with the dynamo backend");
        DfsRepositoryDescription repoDesc = new DfsRepositoryDescription();
        return new NessieRepository.Builder().setRepositoryDescription(repoDesc)
                                             .setBackend(backend.gitBackend())
                                             .setRefBackend(backend.gitRefBackend())
                                             .build();
      case DISK:
        LOGGER.info("JGit Version store has been configured with the file backend");
        File jgitDir = new File(config.getVersionStoreJGitConfig().getJgitDirectory()
                                      .orElseThrow(() -> new RuntimeException("Please set nessie.version.store.jgit.directory")));
        if (!jgitDir.exists()) {
          if (!jgitDir.mkdirs()) {
            throw new RuntimeException(
              String.format("Couldn't create file at %s", config.getVersionStoreJGitConfig().getJgitDirectory().get()));
          }
        }
        LOGGER.info(String.format("File backend is at %s", jgitDir.getAbsolutePath()));
        return Git.init().setDirectory(jgitDir).call().getRepository();
      case INMEMORY:
        LOGGER.info("JGit Version store has been configured with the in memory backend");
        return new InMemoryRepository.Builder().setRepositoryDescription(new DfsRepositoryDescription()).build();
      default:
        throw new RuntimeException(String.format("unknown jgit repo type %s", config.getVersionStoreJGitConfig().getJgitStoreType()));
    }
  }
}
