/*
 * Copyright 2000-2010 JetBrains s.r.o.
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
package org.community.intellij.plugins.communitycase.history.browser;

import com.intellij.lifecycle.PeriodicalTasksCloser;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.vcs.AbstractVcsHelper;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ObjectsConvertor;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.vcs.checkin.CheckinEnvironment;
import com.intellij.openapi.vcs.ui.VcsBalloonProblemNotifier;
import com.intellij.openapi.vfs.newvfs.RefreshSessionImpl;
import com.intellij.util.Consumer;
import org.community.intellij.plugins.communitycase.Vcs;

import java.util.*;

public class CherryPicker {
  private final Vcs myVcs;
  private final List<Commit> myCommits;
  private final LowLevelAccess myAccess;

  private final List<VcsException> myExceptions;
  private final List<VcsException> myWarnings;
  private final List<FilePath> myDirtyFiles;
  private final List<String> myMessagesInOrder;
  private final Map<String, Collection<FilePath>> myFilesToMove;

  public CherryPicker(Vcs vcs, final List<Commit> commits, LowLevelAccess access) {
    myVcs = vcs;
    myCommits = commits;
    myAccess = access;

    myExceptions = new ArrayList<VcsException>();
    myWarnings = new ArrayList<VcsException>();

    myDirtyFiles = new ArrayList<FilePath>();
    myMessagesInOrder = new ArrayList<String>(commits.size());
    myFilesToMove = new HashMap<String, Collection<FilePath>>();
  }

  public void execute() {
    final CheckinEnvironment ce = myVcs.getCheckinEnvironment();

    for (int i = 0; i < myCommits.size(); i++) {
      cherryPickStep(ce, i);
    }

    // remove those that are in newer lists
    checkListsForSamePaths();

    final RefreshSessionImpl refreshSession = new RefreshSessionImpl(true, false, new Runnable() {
      public void run() {
        findAndProcessChangedForVcs();
      }
    });
    refreshSession.addAllFiles(ObjectsConvertor.convert(myDirtyFiles, ObjectsConvertor.FILEPATH_TO_VIRTUAL, ObjectsConvertor.NOT_NULL));
    refreshSession.launch();

    showResults();
  }

  private void findAndProcessChangedForVcs() {
    final ChangeListManager clm = PeriodicalTasksCloser.getInstance().safeGetComponent(myVcs.getProject(), ChangeListManager.class);
    clm.invokeAfterUpdate(new Runnable() {
      public void run() {
        moveToCorrectLists(clm);
      }
    }, InvokeAfterUpdateMode.SILENT, "", new Consumer<VcsDirtyScopeManager>() {
      public void consume(VcsDirtyScopeManager vcsDirtyScopeManager) {
        vcsDirtyScopeManager.filePathsDirty(myDirtyFiles, null);
      }
    }, ModalityState.NON_MODAL);
  }

  private void showResults() {
    if (myExceptions.isEmpty()) {
      VcsBalloonProblemNotifier
        .showOverChangesView(myVcs.getProject(), "Successful cherry-pick into working tree, please commit changes", MessageType.INFO);
    } else {
      VcsBalloonProblemNotifier.showOverChangesView(myVcs.getProject(), "Errors in cherry-pick", MessageType.ERROR);
    }
    if ((! myExceptions.isEmpty()) || (! myWarnings.isEmpty())) {
      myExceptions.addAll(myWarnings);
      AbstractVcsHelper.getInstance(myVcs.getProject()).showErrors(myExceptions, "Cherry-pick problems");
    }
  }

  private void moveToCorrectLists(ChangeListManager clm) {
    for (Map.Entry<String, Collection<FilePath>> entry : myFilesToMove.entrySet()) {
      final Collection<FilePath> filePaths = entry.getValue();
      final String message = entry.getKey();

      if (filePaths.isEmpty()) continue;

      final List<Change> changes = new ArrayList<Change>(filePaths.size());
      for (FilePath filePath : filePaths) {
        changes.add(clm.getChange(filePath));
      }
      if (! changes.isEmpty()) {
        final LocalChangeList cl = clm.addChangeList(message, null);
        clm.moveChangesTo(cl, changes.toArray(new Change[changes.size()]));
      }
    }
  }

  private void checkListsForSamePaths() {
    final GroupOfListsProcessor listsProcessor = new GroupOfListsProcessor();
    listsProcessor.process(myMessagesInOrder, myFilesToMove);
    final Set<String> lostSet = listsProcessor.getHaveLostSomething();
    markFilesMovesToNewerLists(myWarnings, lostSet, myFilesToMove);
  }

  private void cherryPickStep(CheckinEnvironment ce, int i) {
    final Commit commit = myCommits.get(i);
    final ShaHash hash = commit.getHash();
    try {
      myAccess.cherryPick(hash);
    }
    catch (VcsException e) {
      myExceptions.add(e);
    }
    final List<Change> changes = commit.getChanges();

    final Collection<FilePath> paths = ChangesUtil.getPaths(changes);
    String message = ce.getDefaultMessageFor(paths.toArray(new FilePath[paths.size()]));
    message = (message == null) ? new StringBuilder().append(commit.getDescription()).append("(cherry picked from commit ")
      .append(hash.getValue()).append(")").toString() : message;

    myMessagesInOrder.add(message);
    myFilesToMove.put(message, paths);
    myDirtyFiles.addAll(paths);
  }

  private void markFilesMovesToNewerLists(List<VcsException> exceptions, Set<String> lostSet, Map<String, Collection<FilePath>> filesToMove) {
    if (! lostSet.isEmpty()) {
      final StringBuilder sb = new StringBuilder("Some changes are moved from following list(s) to other:");
      boolean first = true;
      for (String s : lostSet) {
        if (filesToMove.get(s).isEmpty()) {
          final VcsException exc =
            new VcsException("Changelist not created since all files moved to other cherry-pick(s): '" + s + "'");
          exc.setIsWarning(true);
          exceptions.add(exc);
          continue;
        }
        sb.append(s);
        if (! first) {
          sb.append(", ");
        }
        first = false;
      }
      if (! first) {
        final VcsException exc = new VcsException(sb.toString());
        exc.setIsWarning(true);
        exceptions.add(exc);
      }
    }
  }

  private static class GroupOfListsProcessor {
    private final Set<String> myHaveLostSomething;

    private GroupOfListsProcessor() {
      myHaveLostSomething = new HashSet<String>();
    }

    public void process(final List<String> messagesInOrder, final Map<String, Collection<FilePath>> filesToMove) {
      // remove those that are in newer lists
      for (int i = 1; i < messagesInOrder.size(); i++) {
        final String message = messagesInOrder.get(i);
        final Collection<FilePath> currentFiles = filesToMove.get(message);

        for (int j = 0; j < i; j++) {
          final String previous = messagesInOrder.get(j);
          final boolean somethingChanged = filesToMove.get(previous).removeAll(currentFiles);
          if (somethingChanged) {
            myHaveLostSomething.add(previous);
          }
        }
      }
    }

    public Set<String> getHaveLostSomething() {
      return myHaveLostSomething;
    }
  }
}
