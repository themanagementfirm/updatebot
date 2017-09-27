/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.fabric8.updatebot.commands;

import io.fabric8.updatebot.kind.Kind;
import io.fabric8.updatebot.kind.Updater;
import io.fabric8.updatebot.model.PushVersionDetails;
import io.fabric8.updatebot.repository.Repositories;
import io.fabric8.updatebot.support.Commands;
import io.fabric8.updatebot.support.GitHubHelpers;
import io.fabric8.utils.Objects;
import org.kohsuke.github.GHCommitPointer;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.kohsuke.github.GHIssueState.OPEN;

/**
 * Base class for all UpdateBot commands
 */
public abstract class ModifyFilesCommandSupport extends CommandSupport {
    private static final transient Logger LOG = LoggerFactory.getLogger(ModifyFilesCommandSupport.class);


    @Override
    public void run(CommandContext context) throws IOException {
        prepareDirectory(context);
        if (doProcess(context) && !context.getConfiguration().isDryRun()) {
            gitCommitAndPullRequest(context);
        }
    }

    public void run(CommandContext context, GHRepository ghRepository, GHPullRequest pullRequest) throws IOException {
        prepareDirectory(context);
        if (doProcess(context)) {
            processPullRequest(context, ghRepository, pullRequest);
        }
    }

    // Implementation methods
    //-------------------------------------------------------------------------
    protected void prepareDirectory(CommandContext context) {
        File dir = context.getRepository().getDir();
        dir.getParentFile().mkdirs();
        Repositories.gitStashAndCheckoutMaster(dir);
    }


    protected boolean doProcess(CommandContext context) throws IOException {
        return false;
    }

    protected void gitCommitAndPullRequest(CommandContext context) throws IOException {
        GHRepository ghRepository = context.gitHubRepository();
        if (ghRepository != null) {
            List<GHPullRequest> pullRequests = ghRepository.getPullRequests(OPEN);

            GHPullRequest pullRequest = findPullRequest(context, pullRequests);
            processPullRequest(context, ghRepository, pullRequest);
        } else {
            // TODO what to do with vanilla git repos?
        }

    }

    protected void processPullRequest(CommandContext context, GHRepository ghRepository, GHPullRequest pullRequest) throws IOException {
        String title = context.createTitle();
        String remoteURL = "git@github.com:" + ghRepository.getOwnerName() + "/" + ghRepository.getName();
        File dir = context.getDir();
        if (Commands.runCommandIgnoreOutput(dir, "git", "remote", "set-url", "origin", remoteURL) != 0) {
            LOG.warn("Could not set the remote URL of " + remoteURL);
        }

        String commandComment = createPullRequestComment();

        if (pullRequest == null) {
            String localBranch = "updatebot-" + UUID.randomUUID().toString();
            doCommit(context, dir, localBranch);

            String body = context.createPullRequestBody();
            //String head = getGithubUsername() + ":" + localBranch;
            String head = localBranch;

            if (Commands.runCommand(dir, "git", "push", "-f", "origin", localBranch) != 0) {
                LOG.warn("Failed to push branch " + localBranch + " for " + context.getCloneUrl());
                return;
            }
            pullRequest = ghRepository.createPullRequest(title, head, "master", body);
            LOG.info("Created pull request " + pullRequest.getHtmlUrl());

            pullRequest.comment(commandComment);
            pullRequest.setLabels(context.getConfiguration().getGithubPullRequestLabel());
        } else {
            String oldTitle = pullRequest.getTitle();
            if (Objects.equal(oldTitle, title)) {
                // lets check if we need to rebase
                if (context.getConfiguration().isRebaseMode()) {
                    if (GitHubHelpers.isMergeable(pullRequest)) {
                        return;
                    }
                    pullRequest.comment("[UpdateBot](https://github.com/fabric8io/updatebot) rebasing due to merge conflicts");
                }
            } else {
                //pullRequest.comment("Replacing previous commit");
                pullRequest.setTitle(title);

                pullRequest.comment(commandComment);
            }

            GHCommitPointer head = pullRequest.getHead();
            String remoteRef = head.getRef();

            String localBranch = remoteRef;

            // lets remove any local branches of this name
            Commands.runCommandIgnoreOutput(dir, "git", "branch", "-D", localBranch);

            doCommit(context, dir, localBranch);

            if (Commands.runCommand(dir, "git", "push", "-f", "origin", localBranch + ":" + remoteRef) != 0) {
                LOG.warn("Failed to push branch " + localBranch + " to existing github branch " + remoteRef + " for " + pullRequest.getHtmlUrl());
            }
            LOG.info("Updated PR " + pullRequest.getHtmlUrl());
        }
    }

    private boolean doCommit(CommandContext context, File dir, String branch) {
        String commitComment = context.createCommit();
        if (Commands.runCommandIgnoreOutput(dir, "git", "checkout", "-b", branch) == 0) {
            if (Commands.runCommandIgnoreOutput(dir, "git", "add", "*") == 0) {
                if (Commands.runCommand(dir, "git", "commit", "-m", commitComment) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Lets try find a pull request for previous PRs
     */
    protected GHPullRequest findPullRequest(CommandContext context, List<GHPullRequest> pullRequests) {
        String prefix = context.createTitlePrefix();
        if (pullRequests != null) {
            for (GHPullRequest pullRequest : pullRequests) {
                String title = pullRequest.getTitle();
                if (title != null && title.startsWith(prefix)) {
                    return pullRequest;
                }
            }
        }
        return null;
    }

    protected boolean pushVersion(CommandContext parentContext, PushVersionDetails step) throws IOException {
        Kind kind = step.getKind();
        Updater updater = kind.getUpdater();
        PushVersionChangesContext context = new PushVersionChangesContext(parentContext, step);
        if (updater.isApplicable(context)) {
            boolean updated = updater.pushVersions(context);
            if (!updated) {
                parentContext.removeChild(context);
            }
            return updated;
        }
        return false;
    }

    protected boolean pushVersions(CommandContext parentContext, List<PushVersionDetails> steps) throws IOException {
        boolean answer = false;
        for (PushVersionDetails step : steps) {
            if (pushVersion(parentContext, step)) {
                answer = true;
            }
        }
        return answer;
    }
}
