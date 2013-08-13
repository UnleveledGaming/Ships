package cuchaz.ships;

import java.io.File;
import java.util.Arrays;

import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.common.DummyModContainer;
import cpw.mods.fml.common.LoadController;
import cpw.mods.fml.common.ModMetadata;
import cpw.mods.fml.common.event.FMLConstructionEvent;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.network.FMLNetworkHandler;
import cpw.mods.fml.common.network.IGuiHandler;
import cpw.mods.fml.common.network.NetworkMod;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.EntityRegistry;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.common.registry.LanguageRegistry;
import cuchaz.modsShared.FMLHacker;
import cuchaz.ships.gui.Gui;
import cuchaz.ships.gui.GuiString;
import cuchaz.ships.packets.PacketHandler;
import cuchaz.ships.packets.PacketLaunchShip;
import cuchaz.ships.packets.PacketPilotShip;
import cuchaz.ships.packets.PacketUnlaunchShip;

// Mod annotations don't work in core mod land
//@Mod( modid="cuchaz.ships", name="Ships", version="0.1", useMetadata=false, dependencies="required-after:cuchaz.ships.core@[0.1,)" )
@NetworkMod(
	// NOTE: 20-character limit for channel names
	channels = { PacketLaunchShip.Channel, PacketUnlaunchShip.Channel, PacketPilotShip.Channel },
	packetHandler = PacketHandler.class
)
public class Ships extends DummyModContainer
{
	public static Ships instance = null;
	
	// materials
	public static final Material MaterialAirWall = new MaterialAirWall( MapColor.airColor );
	
	// (apparently the most robust id picking strategy is almost complete randomness)
	// item registration: use ids [7321-7325]
	public static final ItemPaddle ItemPaddle = new ItemPaddle( 7321 );
	public static final ItemMagicBucket ItemMagicBucket = new ItemMagicBucket( 7322 );
	public static final ItemMagicShipLevitator ItemMagicShipLevitator = new ItemMagicShipLevitator( 7323 );
	
	// block registration: use ids [3170-3190]
	public static final BlockShip BlockShip = new BlockShip( 3170 );
	public static final BlockAirWall BlockAirWall = new BlockAirWall( 3171 );
	
	// entity registration
	public static final int EntityShipId = 174;
	
	private File m_source;
	
	public Ships( )
	{
		super( new ModMetadata() );
		ModMetadata meta = getMetadata();
		meta.modId = "cuchaz.ships";
		meta.name = "Ships";
		meta.version = "0.1";
		meta.authorList = Arrays.asList( new String[] { "Cuchaz" } );
		meta.description = "Build sailable ships out of blocks.";
		meta.url = "";
		
		m_source = FMLHacker.getModSource( getClass() );
		
		// make sure instance semantics are being preserved in core mod land
		if( instance != null )
		{
			throw new Error( "An instance of ships was already active!" );
		}
		instance = this;
	}
	
	@Override
	public boolean registerBus( EventBus bus, LoadController controller )
	{
		bus.register( this );
		return true;
	}
	
	@Override
	public Object getMod( )
	{
		return this;
	}
	
	@Override
    public boolean isNetworkMod( )
    {
        return true;
    }
	
	@Override
	public boolean isImmutable( )
	{
		return false;
	}
	
	@Override
	public File getSource( )
	{
		return m_source;
	}
	
	@Subscribe
	public void construct( FMLConstructionEvent event )
	{
		// the event dispatcher swallows exceptions, so report them here
		try
		{
			// this is where the magic happens
			FMLHacker.unwrapModContainer( this );
			
			// add our container to the ASM data table
			event.getASMHarvestedData().addContainer( this );
	        
			// register for network support
			FMLNetworkHandler.instance().registerNetworkMod( this, getClass(), event.getASMHarvestedData() );
		}
		catch( RuntimeException ex )
		{
			ex.printStackTrace( System.err );
			throw ex;
		}
	}
	
	@Subscribe
	public void load( FMLInitializationEvent event )
	{
		// the event dispatcher swallows exceptions, so report them here
		try
		{
			loadThings();
			loadLanguage();
			loadRecipes();
			
			// set renderers
			RenderingRegistry.registerEntityRenderingHandler( EntityShip.class, new RenderShip() );
			
			// GUI hooks
			NetworkRegistry.instance().registerGuiHandler( this, new IGuiHandler( )
			{
				@Override
				public Object getServerGuiElement( int id, EntityPlayer player, World world, int x, int y, int z )
				{
					return Gui.values()[id].getContainer( player, world, x, y, z );
				}
				
				@Override
				public Object getClientGuiElement( int id, EntityPlayer player, World world, int x, int y, int z )
				{
					return Gui.values()[id].getGui( player, world, x, y, z );
				}
			} );
		}
		catch( RuntimeException ex )
		{
			ex.printStackTrace( System.err );
			throw ex;
		}
	}
	
	private void loadThings( )
	{
		// blocks
		GameRegistry.registerBlock( BlockShip, "blockShip" );
		GameRegistry.registerBlock( BlockAirWall, "blockAirWall" );
		
		// items
		GameRegistry.registerItem( ItemPaddle, "paddle" );
		GameRegistry.registerItem( ItemMagicBucket, "magicBucket" );
		GameRegistry.registerItem( ItemMagicShipLevitator, "magicShipLevitator" );
		
		// entities
		EntityRegistry.registerGlobalEntityID( EntityShip.class, "Ship", EntityShipId );
		EntityRegistry.registerModEntity( EntityShip.class, "Ship", EntityShipId, this, 256, 10, true );
	}
	
	private void loadLanguage( )
	{
		// block names
		LanguageRegistry.addName( BlockShip, "Ship" );
		LanguageRegistry.addName( BlockAirWall, "Air Wall" );
		
		// item names
		LanguageRegistry.addName( ItemPaddle, "Paddle" );
		LanguageRegistry.addName( ItemMagicBucket, "Magic Bucket" );
		LanguageRegistry.addName( ItemMagicShipLevitator, "Magic Ship Levitator" );
		
		// gui strings
		for( GuiString string : GuiString.values() )
		{
			LanguageRegistry.instance().addStringLocalization( string.getKey(), string.getUnlocalizedText() );
		}
	}

	private void loadRecipes( )
	{
		ItemStack ironStack = new ItemStack( Item.ingotIron );
		ItemStack redstoneStack = new ItemStack( Item.redstone );
		ItemStack stickStack = new ItemStack( Item.stick );
		ItemStack goldStack = new ItemStack( Item.ingotGold );
		
		// ship
		GameRegistry.addRecipe(
			new ItemStack( BlockShip ),
			"xxx", "xyx", "xxx",
			'x', ironStack,
			'y', redstoneStack
		);
		
		// paddle
		GameRegistry.addRecipe(
			new ItemStack( ItemPaddle, 1 ),
			" xx", " xx", "x  ",
			'x', stickStack
		);
		
		// magic bucket
		GameRegistry.addRecipe(
			new ItemStack( ItemMagicBucket, 1 ),
			"   ", "x x", " x ",
			'x', goldStack
		);
	}
}