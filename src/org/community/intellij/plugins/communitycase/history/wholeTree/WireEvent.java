package org.community.intellij.plugins.communitycase.history.wholeTree;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ArrayUtil;
import org.jetbrains.annotations.Nullable;

/**
* @author irengrig
 *
 * commits with 1 start and end just belongs to its wire
*/
public class WireEvent {
  private final int myCommitIdx;
  // wire # can be taken from commit
  @Nullable
  private int[] myCommitsEnds;      // branch point   |/.       -1 here -> start of a wire
  @Nullable
  private int[] myWireEnds;
  private int[] myCommitsStarts;    // merge commit   |\  parents here. -1 here -> no parents, i.e. break

  public WireEvent(final int commitIdx, final int[] commitsEnds) {
    myCommitIdx = commitIdx;
    myCommitsEnds = commitsEnds;
    myCommitsStarts = ArrayUtil.EMPTY_INT_ARRAY;
    myWireEnds = null;
  }

  public int getCommitIdx() {
    return myCommitIdx;
  }

  public void addStart(final int idx) {
    myCommitsStarts = ArrayUtil.append(myCommitsStarts, idx);
  }

  public void addWireEnd(final int idx) {
    if (myWireEnds == null) {
      myWireEnds = new int[]{idx};
    } else {
      myWireEnds = ArrayUtil.append(myWireEnds, idx);
    }
  }

  @Nullable
  public int[] getWireEnds() {
    return myWireEnds;
  }

  public void setCommitEnds(final int [] ends) {
    myCommitsEnds = ends;
  }

  @Nullable
  public int[] getCommitsEnds() {
    return myCommitsEnds;
  }

  public int[] getCommitsStarts() {
    return myCommitsStarts;
  }

  // no parent commit present in quantity or exists
  public boolean isEnd() {
    return myCommitsStarts.length == 1 && myCommitsStarts[0] == -1;
  }

  public boolean isStart() {
    return myCommitsEnds != null && myCommitsEnds.length == 1 && myCommitsEnds[0] == -1;
  }

  @Override
  public String toString() {
    return "WireEvent{" +
           "myCommitIdx=" + myCommitIdx +
           ", myCommitsEnds=" + ((myCommitsEnds == null) ? "null" : StringUtil.join(myCommitsEnds, ", ")) +
           ", myWireEnds=" + ((myWireEnds == null) ? "null" : StringUtil.join(myWireEnds, ", ")) +
           ", myCommitsStarts=" + ((myCommitsStarts == null) ? "null" : StringUtil.join(myCommitsStarts, ", ")) +
           '}';
  }
}
