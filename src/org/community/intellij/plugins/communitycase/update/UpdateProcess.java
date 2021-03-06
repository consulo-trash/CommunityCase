/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package org.community.intellij.plugins.communitycase.update;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Clock;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.update.UpdatedFiles;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.text.DateFormatUtil;
import org.community.intellij.plugins.communitycase.Vcs;
import org.community.intellij.plugins.communitycase.commands.Command;
import org.community.intellij.plugins.communitycase.commands.LineHandler;
import org.community.intellij.plugins.communitycase.config.VcsSettings;
import org.community.intellij.plugins.communitycase.history.HistoryUtils;
import org.community.intellij.plugins.communitycase.merge.MergeChangeCollector;

import java.util.List;

/**
 * This class encapsulates update operation from update environment. This allows to customize this operation when needed.
 */
public class UpdateProcess extends BaseRebaseProcess {

  /**
   * The settings to use
   */
  private final VcsSettings mySettings;
  /**
   * The updated files
   */
  private final UpdatedFiles myUpdatedFiles;
  /**
   * The revision that was before update
   */
  private VcsRevisionNumber myBefore;

  /**
   * The constructor
   *
   * @param project      the project instance
   * @param settings     the vcs settings
   * @param vcs          the vcs instance
   * @param updatedFiles the collection where set of updated files is shown
   * @param exceptions   the collection with exceptions
   */
  public UpdateProcess(final Project project,
                       VcsSettings settings,
                       final Vcs vcs,
                       UpdatedFiles updatedFiles,
                       List<VcsException> exceptions) {
    super(vcs, project, exceptions);
    mySettings = settings;
    myUpdatedFiles = updatedFiles;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected LineHandler makeStartHandler(VirtualFile root) {
    // do pull
    LineHandler h = new LineHandler(myProject, root, Command.PULL);
    // ignore merge failure for the pull
    h.ignoreErrorCode(1);
    switch (mySettings.getUpdateType()) {
      case REBASE:
        h.addParameters("--rebase");
        break;
      case MERGE:
        h.addParameters("--no-rebase");
        break;
      case BRANCH_DEFAULT:
        // use default for the branch
        break;
      default:
        assert false : "Unknown update type: " + mySettings.getUpdateType();
    }
    h.addParameters("--no-stat");
    h.addParameters("-v");
    return h;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void markStart(VirtualFile root) throws VcsException {
    // remember the current position
    myBefore=HistoryUtils.getCurrentRevision(myProject,root);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void markEnd(VirtualFile root, final boolean cancelled) {
    // find out what have changed, this is done even if the process was cancelled.
    MergeChangeCollector collector = new MergeChangeCollector(myProject, root, myBefore, myUpdatedFiles);
    collector.collect(myExceptions);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String makeStashMessage() {
    return "Uncommitted changes before update operation at " +
           DateFormatUtil.formatDateTime(Clock.getTime());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected VcsSettings.UpdateChangesPolicy getUpdatePolicy() {
    return mySettings.updateChangesPolicy();
  }
}
