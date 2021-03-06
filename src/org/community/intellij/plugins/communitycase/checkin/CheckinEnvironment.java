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
package org.community.intellij.plugins.communitycase.checkin;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeList;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vcs.ui.RefreshableOnComponent;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.NullableFunction;
import com.intellij.util.PairConsumer;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcsUtil.VcsUtil;
import org.community.intellij.plugins.communitycase.Util;
import org.community.intellij.plugins.communitycase.Vcs;
import org.community.intellij.plugins.communitycase.commands.Command;
import org.community.intellij.plugins.communitycase.commands.FileUtils;
import org.community.intellij.plugins.communitycase.commands.SimpleHandler;
import org.community.intellij.plugins.communitycase.config.VcsSettings;
import org.community.intellij.plugins.communitycase.history.HistoryUtils;
import org.community.intellij.plugins.communitycase.history.NewUsersComponent;
import org.community.intellij.plugins.communitycase.i18n.Bundle;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 * Environment for commit operations.
 */
public class CheckinEnvironment implements com.intellij.openapi.vcs.checkin.CheckinEnvironment {
  private static final Logger log = Logger.getInstance("#"+CheckinEnvironment.class.getName());
  @NonNls private static final String GIT_COMMIT_MSG_FILE_PREFIX = "cc-commit-msg-"; // the file name prefix for commit message file
  @NonNls private static final String GIT_COMMIT_MSG_FILE_EXT = ".txt"; // the file extension for commit message file

  private final Project myProject;
  private final VcsDirtyScopeManager myDirtyScopeManager;
  private final VcsSettings mySettings;

  private boolean myNextCommitGenerate; //the status of the 'generate report' option added to the commit menu
  private Boolean myNextCommitIsPushed = null; // The push option of the next commit


  public CheckinEnvironment(@NotNull Project project, @NotNull final VcsDirtyScopeManager dirtyScopeManager, final VcsSettings settings) {
    myProject = project;
    myDirtyScopeManager = dirtyScopeManager;
    mySettings = settings;
  }

  /** {@inheritDoc} */
  @Override
  public boolean keepChangeListAfterCommit(ChangeList changeList) {
    return false;
  }

  @Override
  public boolean isRefreshAfterCommitNeeded() {
    return false;
  }

  /** {@inheritDoc} */
  @Nullable
  @Override
  public RefreshableOnComponent createAdditionalOptionsPanel(CheckinProjectPanel panel,
                                                             PairConsumer<Object, Object> additionalDataConsumer) {
    return new CheckinOptions(myProject, panel.getRoots());
  }

  @Nullable
  @Override
  public String getDefaultMessageFor(FilePath[] filesToCheckin) {
    StringBuilder rc = new StringBuilder();

    //todo wc get use the checkout message from the first file that has one

    if (rc.length() != 0) {
      return rc.toString();
    }
    return null;
  }

  /** {@inheritDoc} */
  @Override
  public String getHelpId() {
    return null;
  }

  /** {@inheritDoc} */
  @Override
  public String getCheckinOperationName() {
    return Bundle.getString("commit.action.name");
  }

  /** {@inheritDoc} */
  @Override
  public List<VcsException> commit(@NotNull List<Change> changes,
                                   @NotNull String message,
                                   @NotNull NullableFunction<Object, Object> parametersHolder,
                                   @Nullable Set<String> feedback) {
    List<VcsException> exceptions = new ArrayList<VcsException>();
    if (message.length() == 0) {
      //noinspection ThrowableInstanceNeverThrown
      exceptions.add(new VcsException("Empty commit message is not supported for the Git"));
      return exceptions;
    }
    Map<VirtualFile, List<Change>> sortedChanges = sortChangesByGitRoot(changes, exceptions);
    for (Map.Entry<VirtualFile, List<Change>> entry : sortedChanges.entrySet()) {
      Set<FilePath> files = new HashSet<FilePath>();
      final VirtualFile root = entry.getKey();
      try {
        File messageFile = createMessageFile(root, message);
        try {
          final Set<FilePath> added = new HashSet<FilePath>();
          final Set<FilePath> modified = new HashSet<FilePath>();
          final Set<FilePath> removed = new HashSet<FilePath>();
          for (Change change : entry.getValue()) {
            switch (change.getType()) {
              case NEW:
                added.add(change.getAfterRevision().getFile());
                break;
              case MODIFICATION:
                modified.add(change.getAfterRevision().getFile());
                break;
              case DELETED:
                removed.add(change.getBeforeRevision().getFile());
                break;
              case MOVED:
                added.add(change.getAfterRevision().getFile());
                removed.add(change.getBeforeRevision().getFile());
                break;
              default:
                throw new IllegalStateException("Unknown change type: " + change.getType());
            }
          }
          try {
            if (updateIndex(myProject, root, added, removed, exceptions)) {
              try {
                files.addAll(added);
                files.addAll(modified);
                files.addAll(removed);
                commit(myProject, root, files, messageFile, myNextCommitGenerate);
              }
              catch (VcsException ex) {
                if (!isMergeCommit(ex)) {
                  throw ex;
                }
                if (!mergeCommit(myProject, root, added, removed, modified, messageFile, exceptions)) {
                  throw ex;
                }
              }
            }
          }
          finally {
            if (!messageFile.delete()) {
              log.warn("Failed to remove temporary file: " + messageFile);
            }
          }
        }
        catch (VcsException e) {
          exceptions.add(e);
        }
      }
      catch (IOException ex) {
        //noinspection ThrowableInstanceNeverThrown
        exceptions.add(new VcsException("Creation of commit message file failed", ex));
      }
      catch(Exception e) {
        //noinspection ThrowableInstanceNeverThrown
        exceptions.add(new VcsException(e));
      }
    }
    if (myNextCommitIsPushed != null && myNextCommitIsPushed.booleanValue() && exceptions.isEmpty()) {
      // push
      UIUtil.invokeLaterIfNeeded(new Runnable() {
        public void run() {
          PushActiveBranchesDialog.showDialogForProject(myProject);
        }
      });
    }
    return exceptions;
  }

  /** {@inheritDoc} */
  @Override
  public List<VcsException> commit(List<Change> changes, String preparedComment) {
    //noinspection unchecked
    return commit(changes, preparedComment, NullableFunction.NULL, null);
  }

  /**
   * Preform a merge commit
   *
   *
   * @param project     a project
   * @param root        a vcs root
   * @param added       added files
   * @param removed     removed files
   * @param modified    modified files
   * @param messageFile a message file for commit
   * @param exceptions  the list of exceptions to report    @return true if merge commit was successful
   * */
  private static boolean mergeCommit(final Project project,
                                     final VirtualFile root,
                                     final Set<FilePath> added,
                                     final Set<FilePath> removed,
                                     final Set<FilePath> modified,
                                     final File messageFile,
                                     List<VcsException> exceptions) {
/*    HashSet<FilePath> realAdded = new HashSet<FilePath>();
    HashSet<FilePath> realRemoved = new HashSet<FilePath>();
    // perform diff
    SimpleHandler diff = new SimpleHandler(project, root, Command.DIFF);
    diff.setRemote(true);
    diff.setSilent(true);
    diff.setStdoutSuppressed(true);
    diff.addParameters("--diff-filter=ADMRUX", "--name-status", "HEAD");
    diff.endOptions();
    String output;
    try {
      output = diff.run();
    }
    catch (VcsException ex) {
      exceptions.add(ex);
      return false;
    }
    String rootPath = root.getPath();
    for (StringTokenizer lines = new StringTokenizer(output, "\n", false); lines.hasMoreTokens();) {
      String line = lines.nextToken().trim();
      if (line.length() == 0) {
        continue;
      }
      String[] tk = line.split("[ \t]+");
      switch (tk[0].charAt(0)) {
        case 'M':
        case 'A':
          realAdded.add(VcsUtil.getFilePath(rootPath + "/" + tk[tk.length - 1]));
          break;
        case 'D':
          realRemoved.add(VcsUtil.getFilePathForDeletedFile(rootPath + "/" + tk[tk.length - 1], false));
          break;
        default:
          throw new IllegalStateException("Unexpected status: " + line);
      }
    }
    realAdded.removeAll(added);
    realRemoved.removeAll(removed);
*/
    //if (realAdded.size() != 0 || realRemoved.size() != 0) {
    TreeSet<String> files = new TreeSet<String>();
    /*
      for (FilePath f : realAdded) {
        files.add(f.getPresentableUrl());
      }
      for (FilePath f : realRemoved) {
        files.add(f.getPresentableUrl());
      }
      */
    for (FilePath f : added) {
      files.add(f.getPresentableUrl());
    }
    for (FilePath f : removed) {
      files.add(f.getPresentableUrl());
    }
    for (FilePath f : modified) {
      files.add(f.getPresentableUrl());
    }
    final StringBuilder fileList = new StringBuilder();
    for (String f : files) {
      //noinspection HardCodedStringLiteral
      fileList.append("<li>");
      fileList.append(StringUtil.escapeXml(f));
      fileList.append("</li>");
    }
    final int[] rc = new int[1];
    try {
      EventQueue.invokeAndWait(new Runnable() {
        public void run() {
          rc[0] = Messages.showOkCancelDialog(project, Bundle.message("commit.partial.merge.message", fileList.toString()),
                                              Bundle.getString("commit.partial.merge.title"), null);

        }
      });
    }
    catch (RuntimeException ex) {
      throw ex;
    }
    catch (Exception ex) {
      throw new RuntimeException("Unable to invoke a message box on AWT thread", ex);
    }
    if (rc[0] != 0) {
      return false;
    }
    // update non-indexed files
    /*if (!updateIndex(project, root, realAdded, realRemoved, exceptions)) {
      return false;
    }
    for (FilePath f : realAdded) {
      VcsDirtyScopeManager.getInstance(project).fileDirty(f);
    }
    for (FilePath f : realRemoved) {
      VcsDirtyScopeManager.getInstance(project).fileDirty(f);
    }*/
    //}
    // perform merge commit
    try {
      SimpleHandler handler = new SimpleHandler(project, root, Command.CHECKIN);
      handler.addParameters("-cfi", messageFile.getAbsolutePath());
      handler.endOptions();
      for(FilePath path:added)
        handler.addParameters(path.getName());
      for(FilePath path:modified)
        handler.addParameters(path.getName());
      for(FilePath path:removed)
        handler.addParameters(path.getName());
      handler.run();
    }
    catch (VcsException ex) {
      exceptions.add(ex);
      return false;
    }
    return true;
  }

  /**
   * Check if commit has failed due to unfinished merge
   *
   * @param ex an exception to examine
   * @return true if exception means that there is a partial commit during merge
   */
  private static boolean isMergeCommit(final VcsException ex) {
    //noinspection HardCodedStringLiteral
    return -1 != ex.getMessage().indexOf("fatal: cannot do a partial commit during a merge.");
  }

  /**
   * Update index (delete and remove files)
   *
   * @param project    the project
   * @param root       a vcs root
   * @param added      added/modified files to commit
   * @param removed    removed files to commit
   * @param exceptions a list of exceptions to update
   * @return true if index was updated successfully
   */
  private static boolean updateIndex(final Project project,
                                     final VirtualFile root,
                                     final Collection<FilePath> added,
                                     final Collection<FilePath> removed,
                                     final List<VcsException> exceptions) {
    boolean rc = true;
    if (!added.isEmpty()) {
      try {
        FileUtils.addPaths(project, root, added);
      }
      catch (VcsException ex) {
        exceptions.add(ex);
        rc = false;
      }
    }
    if (!removed.isEmpty()) {
      try {
        FileUtils.delete(project, root, removed, "--ignore-unmatch");
      }
      catch (VcsException ex) {
        exceptions.add(ex);
        rc = false;
      }
    }
    return rc;
  }

  /**
   * Create a file that contains the specified message
   *
   * @param root    a git repository root
   * @param message a message to write
   * @return a file reference
   * @throws IOException if file cannot be created
   */
  private File createMessageFile(VirtualFile root, final String message) throws IOException {
    // filter comment lines
    File file = FileUtil.createTempFile(GIT_COMMIT_MSG_FILE_PREFIX, GIT_COMMIT_MSG_FILE_EXT);
    file.deleteOnExit();
    //@NonNls String encoding = ConfigUtil.getCommitEncoding(myProject, root);
    Writer out = new OutputStreamWriter(new FileOutputStream(file)); //new OutputStreamWriter(new FileOutputStream(file), encoding);
    try {
      out.write(message);
    }
    finally {
      out.close();
    }
    return file;
  }

  /**
   * {@inheritDoc}
   */
  public List<VcsException> scheduleMissingFileForDeletion(List<FilePath> files) {
    ArrayList<VcsException> rc = new ArrayList<VcsException>();
    Map<VirtualFile, List<FilePath>> sortedFiles;
    try {
      sortedFiles = Util.sortFilePathsByRoot(files);
    }
    catch (VcsException e) {
      rc.add(e);
      return rc;
    }
    for (Map.Entry<VirtualFile, List<FilePath>> e : sortedFiles.entrySet()) {
      try {
        final VirtualFile root = e.getKey();
        FileUtils.delete(myProject, root, e.getValue());
        markRootDirty(root);
      }
      catch (VcsException ex) {
        rc.add(ex);
      }
    }
    return rc;
  }

  /**
   * Prepare delete files handler.
   *
   *
   * @param project          the project
   * @param root             a vcs root
   * @param files            a files to commit
   * @param message          a message file to use
   * @param nextCommitGenerate  true, if the commit should be amended
   * @throws VcsException in case of git problem
   */
  private static void commit(Project project,
                             VirtualFile root,
                             Collection<FilePath> files,
                             File message,
                             boolean nextCommitGenerate)
    throws VcsException {
    //todo wc checkin directories last??
    //todo wc fix directory checkin comments
    for (List<String> paths : FileUtils.chunkPaths(root, files)) {
      SimpleHandler handler = new SimpleHandler(project, root, Command.
              CHECKIN);
      handler.setRemote(true);
      handler.addParameters("-cfi", message.getAbsolutePath());
      handler.endOptions();
      handler.addParameters(paths);
      handler.run();
    }
    if(nextCommitGenerate) {
      //Map<FilePath,VcsRevisionNumber> pathAndVersions=new HashMap<FilePath,VcsRevisionNumber>();
      StringBuilder changes=new StringBuilder();
      for(FilePath fp:files)
        changes.append(Util.relativePath(VcsUtil.getVcsRootFor(project,fp),fp))
                .append("@@")
                .append(HistoryUtils.getCurrentRevision(project,fp))
                .append("\n");
        //pathAndVersions.put(fp,HistoryUtils.getCurrentRevision(project,fp,null));


/*
      JTextArea report=new JTextArea(changes.toString());
      JBPopupFactory.getInstance().createComponentPopupBuilder(report,report).createPopup();
*/
      String title="Checkin Report";
      //Messages.showDialog(project,msg,title,);
      //Messages.showInfoMessage(project,msg,title);

      //TODO create a message report window. Messages.showMessageDialog used to work, but does not anymore.
      //Messages.showMessageDialog(project,changes.toString(),title,null);

      //Messages.showMultilineInputDialog(project,msg,title,"bla",null,null);

      /*
      DialogBuilder db=new DialogBuilder(project);
      db.setCenterPanel(new JTextArea("bla\nfile2 .java\nfile3\n"));
      db.addCloseButton();
      db.show();
      */
      /*
      JBPopupFactory.getInstance().createComponentPopupBuilder(new JTextArea("bla\nfile2 .java\nfile3\n"),null)
              .setResizable(true)
              .setMovable(true)
              .setRequestFocus(true)
              .createPopup()
              .show(new RelativePoint(new Point(0,0)));
      */
      Notifications.Bus.notify(
        new Notification(Vcs.NOTIFICATION_GROUP_ID,
                         Bundle.message("checkin.success.title"),
                         Bundle.getString("checkin.success.message"),
                         NotificationType.INFORMATION),
        project);
    }
  }


  /**
   * {@inheritDoc}
   */
  public List<VcsException> scheduleUnversionedFilesForAddition(List<VirtualFile> files) {
    ArrayList<VcsException> rc = new ArrayList<VcsException>();
    Map<VirtualFile, List<VirtualFile>> sortedFiles;
    try {
      sortedFiles = Util.sortFilesByRoot(files);
    }
    catch (VcsException e) {
      rc.add(e);
      return rc;
    }
    for (Map.Entry<VirtualFile, List<VirtualFile>> e : sortedFiles.entrySet()) {
      try {
        final VirtualFile root = e.getKey();
        FileUtils.addFiles(myProject, root, e.getValue());
        markRootDirty(root);
      }
      catch (VcsException ex) {
        rc.add(ex);
      }
    }
    return rc;
  }

  /**
   * Sort changes by roots
   *
   * @param changes    a change list
   * @param exceptions exceptions to collect
   * @return sorted changes
   */
  private static Map<VirtualFile, List<Change>> sortChangesByGitRoot(@NotNull List<Change> changes, List<VcsException> exceptions) {
    Map<VirtualFile, List<Change>> result = new HashMap<VirtualFile, List<Change>>();
    for (Change change : changes) {
      final ContentRevision afterRevision = change.getAfterRevision();
      final ContentRevision beforeRevision = change.getBeforeRevision();
      // nothing-to-nothing change cannot happen.
      assert beforeRevision != null || afterRevision != null;
      // note that any path will work, because changes could happen within single vcs root
      final FilePath filePath = afterRevision != null ? afterRevision.getFile() : beforeRevision.getFile();
      final VirtualFile vcsRoot;
      try {
        // the parent paths for calculating roots in order to account for submodules that contribute
        // to the parent change. The path "." is never is valid change, so there should be no problem
        // with it.
        vcsRoot = Util.getRoot(filePath.getParentPath());
      }
      catch (VcsException e) {
        exceptions.add(e);
        continue;
      }
      List<Change> changeList = result.get(vcsRoot);
      if (changeList == null) {
        changeList = new ArrayList<Change>();
        result.put(vcsRoot, changeList);
      }
      changeList.add(change);
    }
    return result;
  }

  /**
   * Mark root as dirty
   *
   * @param root a vcs root to rescan
   */
  private void markRootDirty(final VirtualFile root) {
    // Note that the root is invalidated because changes are detected per-root anyway.
    // Otherwise it is not possible to detect moves.
    myDirtyScopeManager.dirDirtyRecursively(root);
  }

  /**
   * Checkin options for git
   */
  private class CheckinOptions implements RefreshableOnComponent {
    /**
     * A container panel
     */
    private final JPanel myPanel;
    /**
     * The 'generate report' checkbox
     */
    private final JCheckBox myGenerate;

    /**
     * A constructor
     *
     * @param project
     * @param roots
     */
    CheckinOptions(Project project, Collection<VirtualFile> roots) {
      myPanel = new JPanel(new GridBagLayout());
      final Insets insets = new Insets(2, 2, 2, 2);
      GridBagConstraints c = new GridBagConstraints();
      c.gridx = 0;
      c.gridy = 0;
      c.anchor = GridBagConstraints.WEST;
      c.insets = insets;
      c.weightx=1.0;
      myGenerate= new JCheckBox(Bundle.getString("commit.generate"));
      //myGenerate.setMnemonic('m');
      myGenerate.setSelected(true); //todo wc make this configurable from CC VCS options
      myGenerate.setToolTipText(Bundle.getString("commit.generate.tooltip"));
      myPanel.add(myGenerate, c);
    }

    private List<String> getUsersList(final Project project, final Collection<VirtualFile> roots) {
      return NewUsersComponent.getInstance(project).get();
    }

    /**
     * {@inheritDoc}
     */
    public JComponent getComponent() {
      return myPanel;
    }

    /**
     * {@inheritDoc}
     */
    public void refresh() {
      myGenerate.setSelected(true); //todo wc fix this...
      myNextCommitIsPushed = null;
    }

    /**
     * {@inheritDoc}
     */
    public void saveState() {
      myNextCommitGenerate=myGenerate.isSelected();
    }

    /**
     * {@inheritDoc}
     */
    public void restoreState() {
      refresh();
    }
  }

  public void setNextCommitIsPushed(Boolean nextCommitIsPushed) {
    myNextCommitIsPushed = nextCommitIsPushed;
  }
}
