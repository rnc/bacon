/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.bacon.pig.impl.pnc;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.URIish;
import org.jboss.pnc.bacon.pig.impl.utils.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: drop it once https://projects.engineering.redhat.com/browse/NCL-1322 is implemented
 *
 * @author Michal Szynkiewicz, michal.l.szynkiewicz@gmail.com <br>
 *         Date: 3/4/19
 */
public class GitRepoInspector {

    private static final Logger log = LoggerFactory.getLogger(GitRepoInspector.class);
    private static final String GIT_REMOTE_NAME = "prod";

    /**
     * Check if branch 'refSpec' is different from the branch used in the last successful build (either temporary or
     * permanent).
     *
     * @param configId
     * @param internalUrl
     * @param refSpec
     * @param temporaryBuild
     * @return
     */
    public static boolean isModifiedBranch(
            String configId,
            String internalUrl,
            String refSpec,
            boolean temporaryBuild) {

        log.info(
                "Trying to check if branch '{}' in '{}' has been modified, compared to latest build of build config '{}'",
                refSpec,
                internalUrl,
                configId);
        File tempDir = FileUtils.mkTempDir("git");
        try (Git git = cloneRepo(internalUrl, tempDir)) {
            Optional<String> latestCommit = headRevision(git, refSpec);

            if (!latestCommit.isPresent()) {
                log.info(
                        "Couldn't find the head of revision for {}. We assume that it is a commit id instead",
                        refSpec);
                latestCommit = Optional.of(refSpec);
            }

            String tagName = getLatestBuiltTag(configId, temporaryBuild);
            Set<String> baseCommitPosibilities = getBaseCommitPossibilities(git, tagName);

            return !baseCommitPosibilities.contains(latestCommit.get());
        } catch (NoSuccessfulBuildException e) {
            log.info(e.getMessage());
        } catch (Exception e) {
            log.warn("Failed trying to check if branch is modified", e);
        } finally {
            try {
                org.apache.commons.io.FileUtils.deleteDirectory(tempDir);
            } catch (IOException e) {
                // not important enough to log
            }
        }
        return false;
    }

    private static Git cloneRepo(String internalUrl, File targetDir) throws GitAPIException, IOException {
        log.debug("Cloning repository {} into {}", internalUrl, targetDir);

        Git git = Git.init().setDirectory(targetDir).call();

        try (Repository repository = git.getRepository()) {
            StoredConfig config = repository.getConfig();
            config.setBoolean("http", null, "sslVerify", false);
            config.save();
        }

        git.remoteAdd().setName(GIT_REMOTE_NAME).setUri(toAnonymous(internalUrl)).call();

        git.fetch().setRemote(GIT_REMOTE_NAME).setTagOpt(TagOpt.FETCH_TAGS).call();
        return git;
    }

    private static Optional<String> headRevision(Git git, String branch) throws GitAPIException, IOException {

        try (Repository repository = git.getRepository()) {

            Optional<Ref> ref = getRef(branch, repository);
            if (ref.isPresent()) {
                ObjectId id = ref.get().getPeeledObjectId();
                if (id == null) {
                    id = ref.get().getObjectId();
                }
                Iterable<RevCommit> commits = git.log().add(id).call();
                return Optional.of(commits.iterator().next().getName());
            } else {
                return Optional.empty();
            }
        }
    }

    /**
     * TODO: smarter check is required, here if a repour tag is on an "upstream" commit TODO: we may miss modifications
     * (because we return here the tag commit and its parent)
     */
    private static Set<String> getBaseCommitPossibilities(Git git, String tagName) throws GitAPIException, IOException {
        log.debug("Getting base commit possibilities for tag: {}", tagName);
        Set<String> result = new HashSet<>();

        try (Repository repository = git.getRepository()) {
            Optional<Ref> ref = getRef(tagName, repository);

            if (ref.isPresent()) {
                ObjectId id = ref.get().getPeeledObjectId() != null ? ref.get().getPeeledObjectId()
                        : ref.get().getObjectId();

                Iterator<RevCommit> log = git.log().add(id).call().iterator();

                result.add(log.next().getName());
                if (log.hasNext()) {
                    result.add(log.next().getName());
                }
                return result;
            } else {
                log.warn("Couldn't find the tag '{}' in the repository", tagName);
                return Collections.emptySet();
            }
        }
    }

    private static String getLatestBuiltTag(String configId, boolean temporaryBuild) {
        log.debug("Getting latest built tag of config id {}, temporary: {}", configId, temporaryBuild);
        BuildInfoCollector.BuildSearchType searchType = temporaryBuild ? BuildInfoCollector.BuildSearchType.TEMPORARY
                : BuildInfoCollector.BuildSearchType.PERMANENT;
        try (BuildInfoCollector buildInfoCollector = new BuildInfoCollector()) {
            PncBuild latestBuild = buildInfoCollector.getLatestBuild(configId, searchType);
            return latestBuild.getScmTag();
        }
    }

    private static URIish toAnonymous(String internalUrl) throws MalformedURLException {
        String uriAsString;
        if (internalUrl.startsWith("git+ssh")) {
            uriAsString = internalUrl.replace("git+ssh", "https").replace(".redhat.com/", ".redhat.com/gerrit/");
        } else {
            uriAsString = internalUrl;
        }
        return new URIish(URI.create(uriAsString).toURL());
    }

    /**
     * Use this helper method to find the ref since we need to specify the GIT remote name to find it
     *
     * @param reference reference to find
     * @param repository Repository
     * @return Optional<Ref>: if not found it is empty
     * @throws IOException
     */
    private static Optional<Ref> getRef(String reference, Repository repository) throws IOException {

        Ref ref = repository.findRef(GIT_REMOTE_NAME + "/" + reference);

        if (ref == null) {
            ref = repository.findRef(reference);
        }
        return Optional.ofNullable(ref);
    }

    private GitRepoInspector() {
    }
}
