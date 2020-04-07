package electroblob.wizardry.inventory;

import electroblob.wizardry.Wizardry;
import electroblob.wizardry.block.BlockBookshelf;
import electroblob.wizardry.event.SpellBindEvent;
import electroblob.wizardry.item.IWorkbenchItem;
import electroblob.wizardry.item.ItemSpellBook;
import electroblob.wizardry.registry.WizardryAdvancementTriggers;
import electroblob.wizardry.registry.WizardryItems;
import electroblob.wizardry.spell.Spell;
import electroblob.wizardry.tileentity.TileEntityArcaneWorkbench;
import electroblob.wizardry.util.ISpellSortable;
import electroblob.wizardry.util.WandHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.common.MinecraftForge;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The container for the arcane workbench GUI.
 * <p></p>
 * The virtual slot system works as follows:<p></p>
 * - The container has two types of slots: {@link SlotBookList} and {@link VirtualSlot}.<br>
 * - The {@code SlotBookList}s are the ones that actually get displayed. They essentially delegate all their
 * functions to the relevant {@code VirtualSlot}.<br>
 * - Each {@code VirtualSlot} refers to a specific slot in another {@link IInventory} nearby, but is not displayed
 * directly on the GUI. They are sorted and filtered according to the GUI input via
 * {@link ContainerArcaneWorkbench#getActiveBookshelfSlots()}.<br>
 * - When a stack is <i>taken</i> from a {@code SlotBookList} (or its current stack is queried), the {@code VirtualSlot}
 * it delegates to depends on the current search term, sort order and state of the linked bookshelves.<br>
 * - When a stack is <i>inserted</i> into a {@code SlotBookList}, the {@code VirtualSlot} it delegates to depends
 * instead on where the stack came from originally and which virtual slots are free.<br>
 * - Finally, the {@code SlotBookList}s are only really used on the client side (though they are included on the server
 * for consistency, just in case). The bookshelf slot delegates to a virtual slot on the client side (which is where
 * the search and sorting is done), and <i>then</i> the click is sent to the server.
 */
public class ContainerArcaneWorkbench extends Container implements ISpellSortable {

	/** The arcane workbench tile entity associated with this container. */
	public TileEntityArcaneWorkbench tileentity;

	public static final ResourceLocation EMPTY_SLOT_CRYSTAL = new ResourceLocation(Wizardry.MODID, "gui/empty_slot_crystal");
	public static final ResourceLocation EMPTY_SLOT_UPGRADE = new ResourceLocation(Wizardry.MODID, "gui/empty_slot_upgrade");

	public static final int CRYSTAL_SLOT = 8;
	public static final int CENTRE_SLOT = 9;
	public static final int UPGRADE_SLOT = 10;
	
	public static final int SLOT_RADIUS = 42;

	public static final int BOOKSHELF_SLOTS_X = 5;
	public static final int BOOKSHELF_SLOTS_Y = 10;

	public static final int PLAYER_INVENTORY_SIZE = 36;

	private List<VirtualSlot> bookshelfSlots = new ArrayList<>();
	private List<VirtualSlot> activeBookshelfSlots = new ArrayList<>();

	private int scroll = 0;
	private ISpellSortable.SortType sortType = ISpellSortable.SortType.TIER;
	private boolean sortDescending = false;
	private String searchText = "";

	public ContainerArcaneWorkbench(IInventory inventory, TileEntityArcaneWorkbench tileentity){

		this.tileentity = tileentity;

		ItemStack wand = tileentity.getStackInSlot(CENTRE_SLOT);

		for(int i = 0; i < 8; i++){
			Slot slot = new SlotItemClassList(tileentity, i, -999, -999, 1, ItemSpellBook.class);
			this.addSlotToContainer(slot);
		}

		this.addSlotToContainer(new SlotItemList(tileentity, CRYSTAL_SLOT, 13, 101, 64,
				WizardryItems.magic_crystal, WizardryItems.crystal_shard, WizardryItems.grand_crystal))
				.setBackgroundName(EMPTY_SLOT_CRYSTAL.toString());

		this.addSlotToContainer(new SlotWorkbenchItem(tileentity, CENTRE_SLOT, 80, 64, this));

		Set<Item> upgrades = new HashSet<>(WandHelper.getSpecialUpgrades()); // Can't be done statically.
		upgrades.add(WizardryItems.arcane_tome);
		upgrades.add(WizardryItems.armour_upgrade);

		this.addSlotToContainer(new SlotItemList(tileentity, UPGRADE_SLOT, 147, 17, 1, upgrades.toArray(new Item[0])))
				.setBackgroundName(EMPTY_SLOT_UPGRADE.toString());

		for(int x = 0; x < 9; x++){
			this.addSlotToContainer(new Slot(inventory, x, 8 + x * 18, 196));
		}

		for(int y = 0; y < 3; y++){
			for(int x = 0; x < 9; x++){
				this.addSlotToContainer(new Slot(inventory, 9 + x + y * 9, 8 + x * 18, 138 + y * 18));
			}
		}

		for(int y = 0; y < BOOKSHELF_SLOTS_Y; y++){
			for(int x = 0; x < BOOKSHELF_SLOTS_X; x++){
				int index = x + y * BOOKSHELF_SLOTS_X;
				this.addSlotToContainer(new SlotBookList(tileentity, UPGRADE_SLOT + 1 + index, -114 + x * 18, 34 + y * 18, this, index));
			}
		}

		refreshBookshelfSlots(); // Must be done last

		this.onSlotChanged(CENTRE_SLOT, wand, null);
	}

	@Override
	public boolean canInteractWith(EntityPlayer player){
		return this.tileentity.isUsableByPlayer(player);
	}
	
	/**
	 * Shows the given slot in the container GUI at the given position. Intended to do the opposite of
	 * {@link ContainerArcaneWorkbench#hideSlot(int, EntityPlayer)}.
	 * @param index The index of the slot to show.
	 * @param x The x position to put the slot in.
	 * @param y The y position to put the slot in.
	 */
	private void showSlot(int index, int x, int y){
		
		Slot slot = this.getSlot(index);
		slot.xPos = x;
		slot.yPos = y;
	}
	
	/**
	 * Hides the given slot from the container GUI (moves it off the screen) and returns its contents to the given
	 * player. If some or all of the items do not fit in the player's inventory, or if the player is null, they are
	 * dropped on the floor.
	 * @param index The index of the slot to hide.
	 * @param player The player that is using this container.
	 */
	private void hideSlot(int index, EntityPlayer player){
		
		Slot slot = this.getSlot(index);
		
		// 'Removes' the slot from the container (moves it off the screen)
		slot.xPos = -999;
		slot.yPos = -999;

		ItemStack stack = slot.getStack();
		// This doesn't cause an infinite loop because slot i can never be a SlotWandArmour. In effect, it's
		// exactly the same as shift-clicking the slot, so why re-invent the wheel?
		ItemStack remainder = this.transferStackInSlot(player, index);

		if(remainder == ItemStack.EMPTY && stack != ItemStack.EMPTY){
			slot.putStack(ItemStack.EMPTY);
			// The second parameter is never used...
			if(player != null) player.dropItem(stack, false);
		}
	}

	/** Called from the central wand/armour slot when its item is changed or removed. */
	// In case I forget again and think it should have @Override: I wrote this!
	public void onSlotChanged(int slotNumber, ItemStack stack, EntityPlayer player){

		if(slotNumber == CENTRE_SLOT){

			if(stack.isEmpty()){
				// If the stack has been removed, hide all the spell book slots
				for(int i = 0; i < CRYSTAL_SLOT; i++){
					this.hideSlot(i, player);
				}

			}else{
				
				if(stack.getItem() instanceof IWorkbenchItem){ // Should always be true here.
					
					int spellSlots = ((IWorkbenchItem)stack.getItem()).getSpellSlotCount(stack);
					
					int centreX = this.getSlot(CENTRE_SLOT).xPos;
					int centreY = this.getSlot(CENTRE_SLOT).yPos;
					
					// Show however many spell book slots are necessary
					for(int i = 0; i < spellSlots; i++){
						
						float angle = i * (2 * (float)Math.PI)/spellSlots;
						int x = centreX + Math.round(SLOT_RADIUS * MathHelper.sin(angle));
						// -cos because +y is downwards
						int y = centreY + Math.round(SLOT_RADIUS * -MathHelper.cos(angle));
						
						showSlot(i, x, y);
					}
					
					// Hide the rest
					for(int i = spellSlots; i < CRYSTAL_SLOT; i++){
						hideSlot(i, player);
					}
					
				}
			}
		}

		// FIXME: It only seems to be syncing correctly when a stack is put into the slot, not taken out.
		// 		  This is because markDirty isn't called in the tileentity, I think.
		//		  You can simulate this using hoppers!
		this.tileentity.sync();
	}

	// FIXME: Shift-clicking a stack of special upgrades when in the arcane workbench causes the whole stack to be
	//		  transferred when it should be just one (this is a bug with vanilla as well - try putting a stack of
	//		  bottles into a brewing stand). I have at least made it so only one gets used now, so it has no impact on
	//		  the game.
	@Override
	public ItemStack transferStackInSlot(EntityPlayer player, int clickedSlotId){

		ItemStack remainder = ItemStack.EMPTY;
		Slot slot = this.inventorySlots.get(clickedSlotId);

		if(slot != null && slot.getHasStack()){

			ItemStack stack = slot.getStack(); // The stack that was there originally
			remainder = stack.copy(); // A copy of that stack

			// Workbench -> inventory/bookshelves
			if(clickedSlotId <= UPGRADE_SLOT){
				// Try to move the stack into the bookshelves. If this fails...
				if(getBookshelfSlots().isEmpty() || !this.mergeItemStack(stack, getBookshelfSlots().get(0).slotNumber,
						getBookshelfSlots().get(getBookshelfSlots().size()-1).slotNumber + 1, false)){
					// ...try to move the stack into the player's inventory. If this fails...
					if(!this.mergeItemStack(stack, UPGRADE_SLOT + 1, UPGRADE_SLOT + 1 + PLAYER_INVENTORY_SIZE, true)){
						return ItemStack.EMPTY; // ...nothing else happens.
					}
				}
			}
			// Bookshelves -> workbench/inventory
			else if(getSlot(clickedSlotId) instanceof VirtualSlot){

				int[] slotRange = findSlotRangeForItem(stack);

				// Try to move the stack into the workbench. If this fails...
				if(slotRange == null || !this.mergeItemStack(stack, slotRange[0], slotRange[1] + 1, false)){
					// ...try to move the stack into the player's inventory. If this fails...
					if(!this.mergeItemStack(stack, UPGRADE_SLOT + 1, UPGRADE_SLOT + 1 + PLAYER_INVENTORY_SIZE, true)){
						return ItemStack.EMPTY; // ...nothing else happens.
					}
				}
			}
			// Inventory -> workbench/bookshelves
			else{

				int[] slotRange = findSlotRangeForItem(stack);

				// Try to move the stack into the workbench. If this fails...
				if(slotRange == null || !this.mergeItemStack(stack, slotRange[0], slotRange[1] + 1, false)){
					// ...try to move the stack into the bookshelves. If this fails...
					if(getBookshelfSlots().isEmpty() || !this.mergeItemStack(stack, getBookshelfSlots().get(0).slotNumber,
							getBookshelfSlots().get(getBookshelfSlots().size()-1).slotNumber + 1, false)){
						return ItemStack.EMPTY; // ...nothing else happens.
					}
				}
			}

			if(stack.getCount() == 0){
				slot.putStack(ItemStack.EMPTY);
			}else{
				slot.onSlotChanged();
			}

			if(stack.getCount() == remainder.getCount()){
				return ItemStack.EMPTY;
			}

			slot.onTake(player, stack);
		}

		return remainder;
	}

	/**
	 * Returns the minimum and maximum IDs (inclusive) of the workbench slots that are appropriate for the given stack,
	 * or null if no slots are appropriate. Note that this does mean the stack <i>will</i> fit, only that it is valid
	 * for all of the slots in the given range, and will fit if there is space for it.
	 * @param stack The stack to find a slot for
	 * @return A 2-element int array of the minimum and maximum slot IDs respectively
	 */
	@Nullable
	private int[] findSlotRangeForItem(ItemStack stack){

		if(this.getSlot(0).isItemValid(stack)){ // Spell books

			ItemStack centreStack = getSlot(CENTRE_SLOT).getStack();

			if(centreStack.getItem() instanceof IWorkbenchItem){
				// Restrict the range to visible slots
				// (How did I not think of this before? Why did I go to the trouble of overriding mergeItemStack? And
				// how did that fix ever work in the first place?!)
				int spellSlots = ((IWorkbenchItem)centreStack.getItem()).getSpellSlotCount(centreStack);
				if(spellSlots > 0){
					return new int[]{0, spellSlots - 1};
				}
			}

		}else if(getSlot(CRYSTAL_SLOT).isItemValid(stack)){
			return new int[]{CRYSTAL_SLOT, CRYSTAL_SLOT};

		}else if(getSlot(CENTRE_SLOT).isItemValid(stack)){
			return new int[]{CENTRE_SLOT, CENTRE_SLOT};

		}else if(getSlot(UPGRADE_SLOT).isItemValid(stack)){
			return new int[]{UPGRADE_SLOT, UPGRADE_SLOT};
		}

		return null; // It won't fit!
	}

	@Override
	public ItemStack slotClick(int slotId, int dragType, ClickType clickTypeIn, EntityPlayer player){

		// -999 is used for slots in the player inventory
		if(slotId > 0 && getSlot(slotId) instanceof SlotBookList){

			ItemStack stack = player.inventory.getItemStack();

			if(!stack.isEmpty() && !getBookshelfSlots().isEmpty()){
				mergeItemStack(stack, getBookshelfSlots().get(0).slotNumber, getBookshelfSlots().get(getBookshelfSlots().size() - 1).slotNumber + 1, false);
				return stack;
			}
		}

		return super.slotClick(slotId, dragType, clickTypeIn, player);
	}

	/**
	 * Called (via {@link electroblob.wizardry.packet.PacketControlInput PacketControlInput}) when the apply button in
	 * the arcane workbench GUI is pressed.
	 */
	// As of 2.1, for the sake of events and neatness of code, this was moved here from TileEntityArcaneWorkbench.
	// As of 4.2, the spell binding/charging/upgrading code was delegated (via IWorkbenchItem) to the items themselves.
	public void onApplyButtonPressed(EntityPlayer player){

		if(MinecraftForge.EVENT_BUS.post(new SpellBindEvent(player, this))) return;
		
		Slot centre = this.getSlot(CENTRE_SLOT);
		
		if(centre.getStack().getItem() instanceof IWorkbenchItem){ // Should always be true, but no harm in checking.
			
			Slot[] spellBooks = this.inventorySlots.subList(0, 8).toArray(new Slot[8]);
			
			if(((IWorkbenchItem)centre.getStack().getItem())
				.onApplyButtonPressed(player, centre, this.getSlot(CRYSTAL_SLOT), this.getSlot(UPGRADE_SLOT), spellBooks)){

				if(player instanceof EntityPlayerMP){
					WizardryAdvancementTriggers.arcane_workbench.trigger((EntityPlayerMP)player, centre.getStack());
				}
			}
		}
	}

	/** Scrolls to the given row number. */
	public void scrollTo(int row){
		this.scroll = row;
	}

	/** Sets the sorting type to the given type, or toggles the sort direction if it is already that type. */
	public void setSortType(ISpellSortable.SortType sortType){

		if(this.sortType == sortType){
			this.sortDescending = !this.sortDescending;
		}else{
			this.sortType = sortType;
			this.sortDescending = false;
		}

		updateActiveBookshelfSlots();
	}

	@Override
	public ISpellSortable.SortType getSortType(){
		return sortType;
	}

	@Override
	public boolean isSortDescending(){
		return sortDescending;
	}

	/** Sets the search text for the bookshelf slots. */
	public void setSearchText(@Nonnull String searchText){
		this.searchText = searchText;
		this.scrollTo(0);
		updateActiveBookshelfSlots();
	}

	/** Returns <b>all</b> bookshelf slots currently linked to this container, including empty ones. The returned list
	 * is a copy of the internal virtual slot list, with any invalid slots removed. */
	public List<VirtualSlot> getBookshelfSlots(){
		List<VirtualSlot> validSlots = new ArrayList<>(bookshelfSlots);
		validSlots.removeIf(s -> !s.isValid());
		return validSlots;
	}

	/** Updates the active bookshelf slots with the current search term and sorting. Client-side only! */
	public void updateActiveBookshelfSlots(){
		activeBookshelfSlots = bookshelfSlots.stream().filter(s -> s.isValid() && !s.getStack().isEmpty()
				// Slot 0 is a convenient way of testing if the item is a valid spell book
				&& this.getSlot(0).isItemValid(s.getStack())
				&& Spell.byMetadata(s.getStack().getMetadata()).matches(searchText))
				// TODO: This doesn't account for non-spell book items at the moment
				.sorted(Comparator.comparing(s -> Spell.byMetadata(s.getStack().getMetadata()),
						sortDescending ? sortType.comparator.reversed() : sortType.comparator))
				.collect(Collectors.toList());
	}

	/** Returns all the {@link VirtualSlot}s that are currently active, sorted according to the current sort order. A
	 * virtual slot is <i>active</i> if it is not empty and its contents match the current search term (if any). */
	public List<VirtualSlot> getActiveBookshelfSlots(){
		return activeBookshelfSlots;
	}

	/** Returns all the {@link VirtualSlot}s that are currently visible on screen (and hence have an associated 'real'
	 * slot), accounting for search and scrolling, and sorted according to the current sort order. */
	public List<VirtualSlot> getVisibleBookshelfSlots(){
		List<VirtualSlot> activeSlots = getActiveBookshelfSlots();
		return activeSlots.subList(BOOKSHELF_SLOTS_X * scroll, activeSlots.size());
	}

	// The following operations are expensive so should not be done every tick!
	// N.B. If we drop the requirement of it working with any container it could potentially be a lot easier since
	// we then always have control over the bookshelf classes

	// TODO: Call this when a bookshelf is added or removed
	/** Called on initialisation, and whenever a bookshelf is added or removed. */
	private void refreshBookshelfSlots(){

		this.inventorySlots.removeAll(bookshelfSlots);
		// TESTME: May need to do this for inventoryItemStacks (probably not though, seems like MC handles it)
		bookshelfSlots.clear();

		for(IInventory bookshelf : BlockBookshelf.findNearbyBookshelves(tileentity.getWorld(), tileentity.getPos(), tileentity)){
			for(int i=0; i<bookshelf.getSizeInventory(); i++){
				VirtualSlot slot = new VirtualSlot(bookshelf, i); // This sets the slot INDEX (for the INVENTORY)
				bookshelfSlots.add(slot);
				this.addSlotToContainer(slot); // This sets the slot NUMBER (for the CONTAINER)
			}
		}

		if(tileentity.getWorld().isRemote) updateActiveBookshelfSlots();

	}

}
