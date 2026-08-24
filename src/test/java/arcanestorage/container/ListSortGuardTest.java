package arcanestorage.container;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * The terminal's item grid must actually apply the chosen sort, not silently discard it.
 *
 * <p>The original shape of this bug: {@code FormItemList} defaulted its {@code sorted} field to true, and in
 * {@code WAIT_FULl} mode it built its elements with {@code insertSortedList} and then sorted them again, both by
 * {@code Comparator.comparing(i -> i.item)} -- {@code InventoryItem.compareTo}, category then display name. It did
 * that to whatever {@code addAllItems} produced. So the form's own comparator ran, and its result was then
 * discarded by the widget underneath it.
 *
 * <p>That shipped, and it was reported as a button that did not refresh the view. It was not: the view rebuilt
 * correctly every time and the order was overwritten afterwards. GROUP hid it, because GROUP is
 * {@code naturalOrder()} -- the very comparator the engine was applying -- so two of the three modes did nothing and
 * the default looked perfect. The fix at the time was one call, {@code itemList.setSorted(false)}, and this file's
 * original test guarded exactly that call's presence.
 *
 * <p><b>That specific guard retired (24 Aug) when the grid itself did.</b> {@code itemList} is a
 * {@code FormContentBox} now, part of the same recursive category tree the crafting tab uses, and a
 * {@code FormContentBox} has no self-sorting field to defeat in the first place -- {@link arcanestorage.ui.CategoryGrouping}
 * sorts each category's own entries exactly once, from the comparator it is given, and nothing downstream re-sorts
 * them. The specific mechanism that caused the original bug cannot recur, because the class that caused it is gone
 * from this path entirely.
 *
 * <p>The general risk -- a sort control changes a field, and that field's value never actually reaches the thing
 * that sorts -- is still worth guarding, just against the new pipeline instead of the old one. See
 * {@link #sortModeChangesReachCategoryGrouping()}.
 */
public class ListSortGuardTest {

   private static final Path FORM =
      Path.of("src/main/java/arcanestorage/container/StorageTerminalContainerForm.java");

   @Test
   public void sortModeChangesReachCategoryGrouping() throws IOException {
      String source = Files.readString(FORM, StandardCharsets.UTF_8);

      // Both the "content changed" and the "sort mode changed" paths must hand the current sort mode's own
      // comparator to the grouping -- not a fixed order, and not the previous call's stale comparator kept around.
      assertTrue(
         "refreshList must rebuild storageGrouping with the current sortMode's comparator, or a network content "
            + "change would reset the visible order to whatever the grouping last remembered",
         source.contains("this.storageGrouping.onEntriesChanged(this.aggregated, this.currentMask(), "
               + "this.sortMode.comparator())"));

      assertTrue(
         "resortStorageTree must call storageGrouping.onSortChanged(this.sortMode.comparator()), or the sort "
            + "button changes sortMode without the grid's own order ever being told to change",
         source.contains("this.storageGrouping.onSortChanged(this.sortMode.comparator())"));
   }

   @Test
   public void everySortModeStillHasABadge() throws IOException {
      String source = Files.readString(FORM, StandardCharsets.UTF_8);

      // The button shows one character for the active mode. A mode added without one would draw nothing there and
      // be indistinguishable from whichever mode preceded it.
      for (String mode : new String[] {"GROUP(", "NAME(", "AMOUNT("}) {
         int at = source.indexOf(mode);
         assertTrue("SortMode." + mode + " should still be declared", at >= 0);

         String declaration = source.substring(at, source.indexOf(')', at));
         assertTrue(mode + " needs a badge character as its second argument", declaration.contains(","));
      }
   }
}
