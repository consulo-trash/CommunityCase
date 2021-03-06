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

import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

public interface TreeViewI {
  void controllerReady();
  void refreshView(@NotNull final List<Commit> commitsToShow, final TravelTicket ticket, ShaHash jumpTarget);
  void showStatusMessage(@NotNull final String message);

  void refreshStarted();
  void refreshFinished();

  void acceptError(String text, VcsException e);
  void acceptHighlighted(final Set<ShaHash> ids);
  void clearHighlighted();

  void acceptDetails(List<CommittedChangeList> changeList);
}
