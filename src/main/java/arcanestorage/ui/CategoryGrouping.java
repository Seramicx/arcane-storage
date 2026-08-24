package arcanestorage.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import necesse.inventory.item.ItemCategory;

/**
 * What a category tree needs to draw itself, kept apart from how it draws: which entries exist, which of
 * them currently pass a filter, and which category each one belongs to and in what order within it.
 *
 * <h2>Why three pieces instead of one filtered, grouped, sorted list</h2>
 *
 * <p>A search keystroke, a sort-mode change, and the network's own contents changing are three different
 * events with three different costs, and collapsing them into one rebuild would pay the most expensive of
 * the three on every one of them. Sorting a few thousand entries on every keystroke of a search box is the
 * concrete cost this avoids. So the three are kept as separate, independently rebuildable pieces:
 *
 * <ul>
 *   <li>{@code flat} -- every candidate entry, in no particular order. Rebuilt only when the underlying
 *   source changes (the network's contents, or the set of craftable recipes).
 *   <li>{@code mask} -- one flag per entry in {@code flat}, whether it currently passes a filter (search
 *   text, stack filter, craftable-only). Rebuilt on a filter change; touches nothing about categories or
 *   ordering.
 *   <li>{@code position} -- for each category, the indices into {@code flat} that belong to it, already in
 *   within-group order. Rebuilt when the source changes (new entries need placing) or when the within-group
 *   order changes (a sort mode change just re-sorts the existing indices); untouched by a filter change.
 * </ul>
 *
 * <p>Indices into {@code flat} rather than copies of {@code T}, so a sort-only rebuild is an {@code int[]}
 * sort against a comparator that reads {@code flat}, not a rebuild of per-entry data the flat list already
 * owns.
 *
 * <h2>What this does not do</h2>
 *
 * <p>No drawing, no {@code Form}, no knowledge of {@link necesse.gfx.forms.components.FormComponent}. A tree
 * component reads {@link #visibleIndices(ItemCategory)} and {@link #isVisible(ItemCategory)} and lays out
 * whatever it likes from there. Matching logic -- what a search term means, what "craftable" means -- stays
 * with the caller, which is the only side that knows it; this class only ever asks "does entry i pass",
 * never how.
 */
public final class CategoryGrouping<T> {

   private final Function<T, ItemCategory> categoryOf;

   private List<T> flat = new ArrayList<>();

   private boolean[] mask = new boolean[0];

   private final HashMap<ItemCategory, int[]> position = new HashMap<>();

   /**
    * @param categoryOf how to find an entry's own (leaf) category. {@link ItemCategory#getItemsCategory} or
    *     {@code ItemCategory.craftingManager.getItemsCategory} wrapped to read the right field off {@code T},
    *     depending on which manager's tree this grouping is drawing.
    */
   public CategoryGrouping(Function<T, ItemCategory> categoryOf) {
      this.categoryOf = categoryOf;
   }

   public List<T> flat() {
      return this.flat;
   }

   public T get(int index) {
      return this.flat.get(index);
   }

   public boolean passes(int index) {
      return this.mask[index];
   }

   /**
    * Replaces the entries entirely. Both {@code mask} and {@code position} are rebuilt from scratch, because
    * an entry moving in or out changes what both mean -- there is nothing to keep from the previous call.
    *
    * @param mask parallel to {@code entries}, whether each one currently passes the active filter. Computed
    *     by the caller so this class never needs to know what a filter is.
    * @param withinGroupOrder how two entries in the same category should order against each other.
    */
   public void onEntriesChanged(
         List<T> entries, boolean[] mask, Comparator<T> withinGroupOrder) {
      this.flat = entries;
      this.mask = mask;
      this.rebuildPosition(withinGroupOrder);
   }

   /** Same entries, a new pass/fail per entry. Categories and ordering are untouched. */
   public void onFilterChanged(boolean[] mask) {
      this.mask = mask;
   }

   /** Same entries, same pass/fail, only the order within each category changes. */
   public void onSortChanged(Comparator<T> withinGroupOrder) {
      this.rebuildPosition(withinGroupOrder);
   }

   private void rebuildPosition(Comparator<T> withinGroupOrder) {
      this.position.clear();
      HashMap<ItemCategory, List<Integer>> byCategory = new HashMap<>();
      for (int i = 0; i < this.flat.size(); i++) {
         ItemCategory category = this.categoryOf.apply(this.flat.get(i));
         if (category != null) {
            byCategory.computeIfAbsent(category, key -> new ArrayList<>()).add(i);
         }
      }

      for (Map.Entry<ItemCategory, List<Integer>> entry : byCategory.entrySet()) {
         List<Integer> indices = entry.getValue();
         indices.sort((a, b) -> withinGroupOrder.compare(this.flat.get(a), this.flat.get(b)));

         int[] asArray = new int[indices.size()];
         for (int i = 0; i < asArray.length; i++) {
            asArray[i] = indices.get(i);
         }

         this.position.put(entry.getKey(), asArray);
      }
   }

   /** Indices into {@link #flat()} belonging directly to {@code category} (not its children), in order. */
   public int[] ownIndices(ItemCategory category) {
      int[] indices = this.position.get(category);
      return indices == null ? EMPTY : indices;
   }

   private static final int[] EMPTY = new int[0];

   /** Entries directly in {@code category} that currently pass the filter, in within-group order. */
   public List<T> visibleIndices(ItemCategory category) {
      int[] indices = this.ownIndices(category);
      List<T> visible = new ArrayList<>(indices.length);
      for (int index : indices) {
         if (this.mask[index]) {
            visible.add(this.flat.get(index));
         }
      }

      return visible;
   }

   /**
    * Whether {@code category} or anything under it has at least one entry passing the filter.
    *
    * <p>A single bottom-up walk over category <i>nodes</i>, not entries -- so its cost is the size of the
    * category tree actually in use, never the size of {@code flat}. Recomputed on every call rather than
    * cached: the tree is already walked once per frame to lay components out, and this walk is the same
    * shape and the same order of magnitude, so caching it would trade a second small structure to keep in
    * sync for a cost this is already paying elsewhere.
    */
   public boolean isVisible(ItemCategory category) {
      for (int index : this.ownIndices(category)) {
         if (this.mask[index]) {
            return true;
         }
      }

      for (ItemCategory child : category.getChildren()) {
         if (this.isVisible(child)) {
            return true;
         }
      }

      return false;
   }
}
