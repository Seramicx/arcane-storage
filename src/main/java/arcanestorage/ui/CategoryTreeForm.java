package arcanestorage.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;

import necesse.engine.ItemCategoryExpandedSetting;
import necesse.engine.window.WindowManager;
import necesse.gfx.forms.ComponentListContainer;
import necesse.gfx.forms.Form;
import necesse.gfx.forms.components.FormBreakLine;
import necesse.gfx.forms.components.FormComponent;
import necesse.gfx.forms.components.FormComponentList;
import necesse.gfx.forms.components.FormContentIconButton;
import necesse.gfx.forms.components.FormInputSize;
import necesse.gfx.forms.components.localComponents.FormLocalLabel;
import necesse.gfx.gameFont.FontOptions;
import necesse.gfx.ui.ButtonColor;
import necesse.inventory.item.ItemCategory;

/**
 * One category's own section of a recursive, collapsible category tree: its header, its own entries laid
 * out in a wrapping grid, and one child section per subcategory that has anything to show.
 *
 * <h2>Modeled on the creative menu, not the workstation</h2>
 *
 * <p>{@code necesse.gfx.forms.presets.creative.CreativeItemsTab.CategoryForm} already solves this problem
 * for the same reason it exists here: browsing something the size of the whole item registry needs real
 * category structure, not a flat list with a filter dropdown. Its shape is copied deliberately -- one
 * indented {@code Form} per category, an expand button, hide entirely when empty -- but the class itself is
 * not reused. It is a non-static inner class wired to {@code CreativeItemsTab}'s own fields
 * ({@code compactMode}, its search thread, its {@code playerClient}) and its leaves are
 * {@code FormContainerCreativeItem}, built to spawn infinite creative-mode copies. None of that fits a
 * withdraw or a craft action, so this ports the structure and takes its leaf type as a type parameter
 * instead.
 *
 * <p>The workstation's own category grouping, by contrast, is the thing this replaces: a single fixed
 * depth, no nesting, no indentation. It existed because nothing here did the recursive version yet, not
 * because the flat version was the better fit -- the mod's own network can hold a large fraction of the
 * game's items by the point a player has one, which is exactly the creative menu's own problem.
 *
 * <h2>What it reads, and what it does not decide</h2>
 *
 * <p>Everything about <i>what counts as a match</i> -- search text, stack filters, craftable-only -- is
 * decided by the caller and baked into {@link CategoryGrouping}'s mask before this class ever runs. This
 * class only asks {@link CategoryGrouping#isVisible} and {@link CategoryGrouping#visibleIndices}; it has no
 * matching logic of its own to keep in sync with either tab's.
 */
public class CategoryTreeForm<T> extends Form {

   /** A leaf's component is constructed already at its assigned position, not repositioned afterwards. */
   public interface LeafFactory<T> {
      FormComponent create(T entry, int x, int y);
   }

   private final CategoryGrouping<T> grouping;

   private final ItemCategory category;

   /**
    * This node's own expand state, or {@code null} only when it could not be resolved -- which happens if
    * {@code category} is somehow not really a child of the setting passed in. Falls back to "always
    * expanded" rather than throwing, since a tree that cannot remember one node's fold state is a smaller
    * problem than a tree that cannot open at all.
    */
   private final ItemCategoryExpandedSetting expanded;

   private final CategoryTreeForm.LeafFactory<T> leafFactory;

   private final int leafElementSize;

   private final boolean isTopLevel;

   private final boolean addBreakLine;

   /**
    * Whether this tree draws any category structure at all -- headers, expand buttons, indentation --
    * or lays every visible entry out as one free-flowing collection with no chrome. False only for
    * {@code GroupingMode.NONE}, where every entry is deliberately assigned to the tree's own root by
    * that mode's {@code categoryOf} function, and this flag is the only thing that then changes: the
    * same recursive walk runs regardless, but a tree with everything on one node draws that one node
    * bare rather than as a "MASTER (1842)" section no player asked to see.
    */
   private final boolean displayCategories;

   private final FormComponentList header;

   private final FormComponentList body;

   /** Null when {@link #displayCategories} is false -- nothing to fold when there is only ever one node. */
   private final FormContentIconButton expandButton;

   private final List<CategoryTreeForm<T>> children = new ArrayList<>();

   /** This node's own header-plus-leaf-grid height, excluding children -- fixed until the next {@link #rebuild}. */
   private int leafContentHeight;

   private int contentHeight;

   /**
    * Notified whenever this node's own height changes for any reason -- collapsing, or a descendant's
    * collapse changing how much space it needs. Set by whichever caller stacked this node next to its
    * siblings ({@link #rebuild} for a parent's direct children, {@link #buildTopLevel} for the top level),
    * so a height change three levels down can reflow every ancestor's siblings without any of them polling.
    */
   private Runnable onHeightChanged;
   /** Pixels a level's content is pushed in from its parent's left edge, matching the shrink in its width. */
   private static final int INDENT = 16;

   /**
    * @param rootExpanded the session's own expand-state root for this tree, e.g. from
    *     {@code Settings.getItemCategoryExpandedSetting(...)} -- the same object every node in one tree is
    *     built from, so a node three levels down still reads and writes the same persisted setting a second
    *     open would see. Every recursive call below passes this straight through unchanged; only the
    *     category argument changes as the tree descends.
    * @param fullWidth the exact width this node itself should occupy, already resolved by the caller for
    *     its own depth -- {@link #rebuild} shrinks this by {@link #INDENT} for each child it places, and
    *     positions that child's left edge in by the same amount, so a level's right edge lines up with its
    *     parent's rather than drifting narrower without ever visibly moving in from the left.
    * @param leafFactory turns one passing entry into the component that draws it. Called once per visible
    *     leaf per rebuild -- not cached across rebuilds, since a component's own state (highlighted, being
    *     dragged) is cheaper to reconstruct than to diff.
    * @param leafElementSize the square size, in pixels, each leaf's component is given in the wrapping grid.
    */
   public CategoryTreeForm(
         CategoryGrouping<T> grouping, ItemCategory category, ItemCategoryExpandedSetting rootExpanded,
         CategoryTreeForm.LeafFactory<T> leafFactory, int leafElementSize, int fullWidth, boolean isTopLevel,
         boolean addBreakLine, boolean displayCategories) {
      super(category.stringID + "CategoryForm", fullWidth, 100);
      // The base Form constructor turns both of these on unconditionally, which is right for a single
      // window but wrong here: one CategoryTreeForm exists per category, nested arbitrarily deep, so
      // leaving them on paints a full opaque panel-plus-border behind every section -- the "boxed
      // accordion tile" look flagged as not fitting. The tab's own outer form already draws the one
      // background a player should see.
      this.drawBase = false;
      this.drawEdge = false;

      // Every child added below -- header, expand icon, body, leaves, and recursively each nested
      // CategoryTreeForm -- inherits its style from `this` at add time, via ComponentList.add calling
      // inheritStyle(parentComponent). That inheritance is what makes one call on a root form reach a
      // whole window (see ArcaneStyles' own doc comment) -- but it only works because the root form is
      // *styled before anything is added to it*. This form breaks that assumption: buildTopLevel calls
      // `new CategoryTreeForm<>(...)` and only styles the *result* by adding it to an already-styled
      // host afterward, which is too late -- every child here is already built and already inherited a
      // null style by the time that happens. The fix mirrors StorageTerminalContainerForm's own
      // `styled(Form)` helper rather than depending on inheritance from a host: ask ArcaneStyles
      // directly, the same way every tab's root form does, so this form is self-sufficient regardless
      // of when its caller gets around to adding it anywhere.
      ArcaneStyles.apply(this);
      this.grouping = grouping;
      this.category = category;
      this.expanded = category.depth == 0 ? rootExpanded : findSetting(rootExpanded, category);
      this.leafFactory = leafFactory;
      this.leafElementSize = leafElementSize;
      this.isTopLevel = isTopLevel;
      this.addBreakLine = addBreakLine;
      this.displayCategories = displayCategories;

      this.header = this.addComponent(new FormComponentList());
      if (displayCategories) {
         if (addBreakLine) {
            this.header.addComponent(new FormBreakLine(FormBreakLine.ALIGN_BEGINNING, 0, 1, this.getWidth() - 4, true));
         }

         int headerY = addBreakLine ? 4 : 0;
         this.expandButton = this.header.addComponent(
               new FormContentIconButton(0, headerY + 2, FormInputSize.SIZE_20, ButtonColor.BASE,
                     this.getInterfaceStyle().button_collapsed_16));
         this.expandButton.onClicked(event -> this.setExpanded(!this.isExpanded(), false));
         this.header.addComponent(
               new FormLocalLabel(category.displayName, new FontOptions(16), -1, 22, headerY + 4, fullWidth));
      } else {
         this.expandButton = null;
      }

      this.body = this.addComponent(new FormComponentList() {
         @Override
         public boolean shouldUseMouseEvents() {
            return false;
         }
      });

      this.rebuild(rootExpanded);
      this.setExpanded(this.isExpanded(), true);
   }

   /**
    * Walks {@code root} down to {@code category}'s own setting, one real parent-child step at a time --
    * {@link ItemCategoryExpandedSetting#getChild} requires each step to be a genuine child of the one
    * before it, so a category three levels deep needs three calls, not one.
    */
   private static ItemCategoryExpandedSetting findSetting(ItemCategoryExpandedSetting root, ItemCategory category) {
      List<ItemCategory> chain = new ArrayList<>();
      for (ItemCategory current = category; current.depth > 0; current = current.parent) {
         chain.add(current);
      }

      ItemCategoryExpandedSetting setting = root;
      for (int i = chain.size() - 1; i >= 0 && setting != null; i--) {
         setting = setting.getChild(chain.get(i));
      }

      return setting;
   }

   private boolean isExpanded() {
      return !this.displayCategories || this.expanded == null || this.expanded.isExpanded();
   }

   /**
    * Rebuilds this node's own leaves and child sections from what {@link CategoryGrouping} currently says is
    * visible. Called once per node whenever the caller's own poll (search text, sort mode, network
    * contents) decides a redraw is needed -- there is no independent dirty-tracking inside this class, for
    * the same reason the crafting tab's own polling comment gives: a redundant rebuild of an unchanged tree
    * costs a pass over data already in memory, and that is cheaper than a listener this component would
    * have to remember to unregister.
    *
    * @param rootExpanded the same root every node in this tree was built from -- passed through again here
    *     rather than stored, so a child created fresh this call is wired to the identical persisted setting
    *     a child kept from the previous call would have been.
    */
   public void rebuild(ItemCategoryExpandedSetting rootExpanded) {
      this.body.clearComponents();
      this.children.clear();

      for (ItemCategory child : this.sortedVisibleChildren()) {
         CategoryTreeForm<T> childForm = this.body.addComponent(
               new CategoryTreeForm<>(this.grouping, child, rootExpanded, this.leafFactory,
                     this.leafElementSize, this.getWidth() - INDENT, false, true, this.displayCategories));
         childForm.onHeightChanged = this::reflowChildren;
         this.children.add(childForm);
      }

      List<T> leaves = this.grouping.visibleIndices(this.category);
      int padding = 2;
      int availableWidth = this.getWidth() - 16;
      int elementSize = this.leafElementSize + padding * 2;
      int perRow = Math.max(1, availableWidth / elementSize);
      int startHeight = this.displayCategories ? 24 + (this.addBreakLine ? 4 : 0) : 0;

      for (int i = 0; i < leaves.size(); i++) {
         int x = i % perRow * elementSize + padding;
         int y = i / perRow * elementSize + startHeight;
         this.body.addComponent(this.leafFactory.create(leaves.get(i), x, y));
      }

      int rows = leaves.isEmpty() ? 0 : (leaves.size() + perRow - 1) / perRow;
      this.leafContentHeight = startHeight + rows * elementSize + (leaves.isEmpty() ? 0 : 4);

      this.reflowChildren();
   }

   /**
    * Repositions {@link #children} one under another from each one's <i>current</i> height, and
    * recomputes this node's own total height to match -- the operation a child's collapse or expand needs
    * done on its parent, without redoing the leaf grid above the children, which a fold never changes.
    */
   private void reflowChildren() {
      int childY = this.leafContentHeight;
      for (CategoryTreeForm<T> childForm : this.children) {
         childForm.setPosition(INDENT, childY);
         childY += childForm.getHeight();
      }

      this.contentHeight = childY;
      this.updateOwnHeight();
   }


   private List<ItemCategory> sortedVisibleChildren() {
      List<ItemCategory> visible = new ArrayList<>();
      this.category.getChildren().forEach(child -> {
         if (this.grouping.isVisible(child)) {
            visible.add(child);
         }
      });
      visible.sort(Comparator.naturalOrder());
      return visible;
   }

   public void setExpanded(boolean isExpanded, boolean force) {
      if (!this.displayCategories) {
         // Nothing to fold when there is only ever one node -- the body always stays visible, and
         // updateOwnHeight below reads contentHeight unconditionally rather than this.isExpanded().
         return;
      }

      boolean changed = force || this.isExpanded() != isExpanded;
      if (this.expanded != null) {
         this.expanded.setExpanded(isExpanded);
      }

      if (changed) {
         this.expandButton.setIcon(isExpanded
               ? this.getInterfaceStyle().button_expanded_16
               : this.getInterfaceStyle().button_collapsed_16);
         this.body.setHidden(!isExpanded);
         this.updateOwnHeight();
         WindowManager.getWindow().submitNextMoveEvent();
      }
   }

   private void updateOwnHeight() {
      int headerHeight = this.displayCategories ? 24 + (this.addBreakLine ? 4 : 0) : 0;
      int newHeight = this.displayCategories && this.body.isHidden() ? headerHeight : this.contentHeight;
      if (newHeight != this.getHeight()) {
         this.setHeight(newHeight);
         if (this.onHeightChanged != null) {
            this.onHeightChanged.run();
         }
      }
   }

   @Override
   public boolean shouldUseMouseEvents() {
      return false;
   }

   /**
    * Builds one top-level section per direct child of {@code root} that has anything to show, plus --
    * only when {@code root} itself has anything assigned directly to it -- one further, un-chromed
    * section for that, stacked vertically, and returns their total height.
    *
    * <p>{@code root} itself is not rendered as a normal section -- {@code ItemCategory.masterCategory}'s
    * own {@code displayName} is the literal string {@code "MASTER"}, never meant to be shown, and
    * vanilla's own {@code CreativeItemsTab.addItems} does not render it either: it iterates the root's
    * children and gives each one its own top-level section. This mirrors that for
    * {@code displayCategories == true}, where nothing is ever assigned directly to the root and the extra
    * section this method can add is therefore always empty and skipped.
    *
    * <p>{@code GroupingMode.NONE} is the one caller that changes this: its own {@code categoryOf}
    * deliberately assigns every entry to {@code root} itself, so this is the one case where root's own
    * bucket has anything to show -- and {@code displayCategories == false} for exactly that caller, so the
    * one section this method then builds draws with no header, indentation, or fold control, reading as a
    * single free-flowing collection rather than a "MASTER (1842)" section nobody asked to see. Same method,
    * same recursive walk, for all three grouping modes -- only the flag differs.
    *
    * @param host where the sections are added as components; not resized or scrolled by this method --
    *     the caller sets its own content box from the returned height, the same way it already sizes any
    *     other content it builds.
    * @param onHeightChanged called with the new total height whenever a section's collapse or expand
    *     changes it after this call returns -- {@code buildTopLevel}'s own return value only covers the
    *     height at construction time, and a fold anywhere in the tree can change it well after that.
    * @param displayCategories whether every section in this tree draws its own header/indent/fold chrome,
    *     or lays its entries out bare. Threaded through unchanged to every node this call creates,
    *     directly and recursively -- one tree is either fully chromed or fully bare, never a mix.
    */
   public static <T> int buildTopLevel(
         ComponentListContainer<FormComponent> host, CategoryGrouping<T> grouping, ItemCategory root,
         ItemCategoryExpandedSetting rootExpanded, CategoryTreeForm.LeafFactory<T> leafFactory,
         int leafElementSize, int width, boolean displayCategories, IntConsumer onHeightChanged) {
      List<ItemCategory> topLevel = new ArrayList<>();
      root.getChildren().forEach(child -> {
         if (grouping.isVisible(child)) {
            topLevel.add(child);
         }
      });
      topLevel.sort(Comparator.naturalOrder());

      List<CategoryTreeForm<T>> sections = new ArrayList<>();
      boolean addBreakLine = false;

      // Root's own bucket, only when it actually has anything -- see the class doc above for which
      // grouping mode that is. Built first so a flat "everything" collection is the whole tree, not a
      // trailing section after empty top-level headers.
      if (grouping.ownIndices(root).length > 0) {
         sections.add(host.addComponent(
               new CategoryTreeForm<>(grouping, root, rootExpanded, leafFactory, leafElementSize, width,
                     true, false, displayCategories)));
         addBreakLine = true;
      }

      for (ItemCategory category : topLevel) {
         CategoryTreeForm<T> section = host.addComponent(
               new CategoryTreeForm<>(grouping, category, rootExpanded, leafFactory, leafElementSize, width,
                     true, addBreakLine, displayCategories));
         sections.add(section);
         addBreakLine = true;
      }

      for (CategoryTreeForm<T> section : sections) {
         section.onHeightChanged = () -> onHeightChanged.accept(reflowTopLevel(sections));
      }

      return reflowTopLevel(sections);
   }

   /** Repositions {@code sections} one under another from each one's current height; returns the total. */
   private static <T> int reflowTopLevel(List<CategoryTreeForm<T>> sections) {
      int y = 0;
      for (CategoryTreeForm<T> section : sections) {
         section.setPosition(0, y);
         y += section.getHeight();
      }

      return y;
   }
}
