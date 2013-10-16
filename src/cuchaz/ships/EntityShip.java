package cuchaz.ships;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import cpw.mods.fml.common.network.PacketDispatcher;
import cuchaz.modsShared.BlockSide;
import cuchaz.modsShared.BoxCorner;
import cuchaz.modsShared.CircleRange;
import cuchaz.modsShared.CompareReal;
import cuchaz.modsShared.RotatedBB;
import cuchaz.ships.packets.PacketPilotShip;
import cuchaz.ships.propulsion.Propulsion;

public class EntityShip extends Entity
{
	public static final int LinearThrottleMax = 100;
	public static final int LinearThrottleMin = -25;
	public static final int LinearThrottleStep = 2;
	public static final int AngularThrottleMax = 1;
	public static final int AngularThrottleMin = -1;
	
	// data watcher IDs. Entity uses [0,1]. We can use [2,31]
	private static final int WatcherIdBlocks = 2;
	private static final int WatcherIdWaterHeight = 3;
	
	private static final double RiderEpsilon = 0.2;
	
	public float motionYaw;
	public int linearThrottle;
	public int angularThrottle;
	
	private ShipWorld m_blocks;
	private ShipPhysics m_physics;
	private Propulsion m_propulsion;
	private double m_shipBlockX;
	private double m_shipBlockY;
	private double m_shipBlockZ;
	private int m_pilotActions;
	private int m_oldPilotActions;
	private BlockSide m_sideShipForward;
	private boolean m_sendPilotChangesToServer;
	private boolean m_hasInfoFromServer;
	private double m_xFromServer;
	private double m_yFromServer;
	private double m_zFromServer;
	private float m_yawFromServer;
	private float m_pitchFromServer;
	private TreeSet<ChunkCoordinates> m_previouslyDisplacedWaterBlocks;
	private ShipCollider m_collider;
	
	public EntityShip( World world )
	{
		super( world );
		yOffset = 0.0f;
		motionX = 0.0;
		motionY = 0.0;
		motionZ = 0.0;
		motionYaw = 0.0f;
		linearThrottle = 0;
		angularThrottle = 0;
		
		m_blocks = null;
		m_physics = null;
		m_propulsion = null;
		m_shipBlockX = 0;
		m_shipBlockY = 0;
		m_shipBlockZ = 0;
		m_pilotActions = 0;
		m_oldPilotActions = 0;
		m_sideShipForward = null;
		m_sendPilotChangesToServer = false;
		m_hasInfoFromServer = false;
		m_xFromServer = 0;
		m_yFromServer = 0;
		m_zFromServer = 0;
		m_yawFromServer = 0;
		m_pitchFromServer = 0;
		m_previouslyDisplacedWaterBlocks = null;
		m_collider = new ShipCollider( this );
	}
	
	@Override
	protected void entityInit( )
	{
		// this gets called inside super.Entity( World )
		// it seems to be used to init the data watcher
		
		// allocate a slot for the block data
		dataWatcher.addObject( WatcherIdBlocks, "" );
		dataWatcher.addObject( WatcherIdWaterHeight, -1 );
	}
	
	public void setBlocks( ShipWorld blocks )
	{
		// if the blocks are invalid, just kill the ship
		if( !blocks.isValid() )
		{
			setDead();
			System.err.println( "Ship world is invalid. Killed ship." );
			return;
		}
		
		// reset the motion again. For some reason, the client entity gets bogus velocities from somewhere...
		motionX = 0.0;
		motionY = 0.0;
		motionZ = 0.0;
		motionYaw = 0.0f;
		
		m_blocks = blocks;
		blocks.setShip( this );
		m_physics = new ShipPhysics( m_blocks );
		m_propulsion = new Propulsion( m_blocks );
		
		// get the ship center of mass so we can convert between ship/block spaces
		Vec3 centerOfMass = m_physics.getCenterOfMass();
		m_shipBlockX = -centerOfMass.xCoord;
		m_shipBlockY = -centerOfMass.yCoord;
		m_shipBlockZ = -centerOfMass.zCoord;
		
		// save the data into the data watcher so it gets sync'd to the client
		dataWatcher.updateObject( WatcherIdBlocks, m_blocks.getDataString() );
		
		computeBoundingBox( boundingBox, posX, posY, posZ, rotationYaw );
		
		// TEMP
		System.out.println( String.format(
			"%s EntityShip initialized at (%.2f,%.2f,%.2f) + (%.4f,%.4f,%.4f)",
			worldObj.isRemote ? "CLIENT" : "SERVER",
			posX, posY, posZ,
			motionX, motionY, motionZ
		) );
		
		ShipLocator.registerShip( this );
	}
	
	@Override
	public void setDead( )
	{
		super.setDead();
		
		// remove all the air wall blocks
		if( m_previouslyDisplacedWaterBlocks != null )
		{
			for( ChunkCoordinates coords : m_previouslyDisplacedWaterBlocks )
			{
				if( worldObj.getBlockId( coords.posX, coords.posY, coords.posZ ) == Ships.m_blockAirWall.blockID )
				{
					worldObj.setBlock( coords.posX, coords.posY, coords.posZ, Block.waterStill.blockID );
				}
			}
		}
		
		// TEMP
		System.out.println( ( worldObj.isRemote ? "CLIENT" : "SERVER" ) + " EntityShip died!" );
		
		ShipLocator.unregisterShip( this );
	}
	
	@Override
	protected void readEntityFromNBT( NBTTagCompound nbt )
	{
		setWaterHeight( nbt.getInteger( "waterHeight" ) );
		setBlocks( new ShipWorld( worldObj, nbt.getByteArray( "blocks" ) ) );
	}
	
	@Override
	protected void writeEntityToNBT( NBTTagCompound nbt )
	{
		nbt.setInteger( "waterHeight", getWaterHeight() );
		nbt.setByteArray( "blocks", m_blocks.getData() );
	}
	
	public ShipWorld getBlocks( )
	{
		return m_blocks;
	}
	
	public Propulsion getPropulsion( )
	{
		return m_propulsion;
	}
	
	public ShipCollider getCollider( )
	{
		return m_collider;
	}
	
	public int getWaterHeight( )
	{
		return dataWatcher.getWatchableObjectInt( WatcherIdWaterHeight );
	}
	public void setWaterHeight( int val )
	{
		dataWatcher.updateObject( WatcherIdWaterHeight, val );
	}
	
	@Override
	public boolean canBeCollidedWith()
    {
        return true;
    }
	
	@Override
	public void setPosition( double x, double y, double z )
	{
		posX = x;
        posY = y;
        posZ = z;
		computeBoundingBox( boundingBox, posX, posY, posZ, rotationYaw );
	}
	
	@Override
	public void setPositionAndRotation2( double x, double y, double z, float yaw, float pitch, int alwaysThree )
	{
		// NOTE: this function should really be called onGetUpdatedPositionFromServer()
		// also, server positions are off by as much as 0.03 in {x,y,z}
		
		// just save the info and we'll deal with it on the next update tick
		m_hasInfoFromServer = true;
		m_xFromServer = x;
		m_yFromServer = y;
		m_zFromServer = z;
		m_yawFromServer = yaw;
		m_pitchFromServer = pitch;
	}
	
	@Override
	public void onUpdate( )
	{
		// did we die already?
		if( isDead )
		{
			return;
		}
		
		// on the client, see if the blocks loaded yet
		if( worldObj.isRemote )
		{
			if( m_blocks == null )
			{
				// do we have blocks from the data watcher?
				String blockData = dataWatcher.getWatchableObjectString( WatcherIdBlocks );
				if( blockData != null && blockData.length() > 0 )
				{
					// then load the blocks
					setBlocks( new ShipWorld( worldObj, blockData ) );
				}
			}
		}
		
		// don't do any updating until we get blocks
		if( m_blocks == null )
		{
			return;
		}
		
		// TEMP: kill all ships
		if( false )
		{
			setDead();
		}
		
		double waterHeightInBlockSpace = shipToBlocksY( worldToShipY( getWaterHeight() ) );
		
		adjustMotionDueToGravityAndBuoyancy( waterHeightInBlockSpace );
		adjustMotionDueToThrustAndDrag( waterHeightInBlockSpace );
		// TEMP: disable all collisions
		//adjustMotionDueToBlockCollisions();
		
		double dx = motionX;
		double dy = motionY;
		double dz = motionZ;
		float dYaw = motionYaw;
		
		// did we get an updated position from the server?
		if( m_hasInfoFromServer )
		{
			// position deltas are easy
			dx += m_xFromServer - posX;
			dy += m_yFromServer - posY;
			dz += m_zFromServer - posZ;
			
			// we need fancy math to get the correct rotation delta
			double yawRadClient = CircleRange.mapMinusPiToPi( Math.toRadians( rotationYaw ) );
			double yawRadServer = CircleRange.mapMinusPiToPi( Math.toRadians( m_yawFromServer ) );
			double yawDelta = CircleRange.newByShortSegment( yawRadClient, yawRadServer ).getLength();
			
			// was the rotation delta actually positive?
			if( !CompareReal.eq( CircleRange.mapMinusPiToPi( yawRadClient + yawDelta ), yawRadServer ) )
			{
				// nope. it's a negative delta
				yawDelta = -yawDelta;
			}
			yawDelta = Math.toDegrees( yawDelta );
			
			dYaw += yawDelta;
			
			// just apply the pitch directly
			rotationPitch = m_pitchFromServer;
			
			m_hasInfoFromServer = false;
		}
		
		final double Epsilon = 1e-3;
		
		/* TEMP
		System.out.println( String.format( "%s Ship movement: p=(%.4f,%.4f,%.4f), d=(%.4f,%.4f,%.4f), dYaw=%.1f",
			worldObj.isRemote ? "CLIENT" : "SERVER",
			posX, posY, posZ,
			dx, dy, dz, dYaw
		) );
		*/
		
		// did we even move a noticeable amount?
		if( Math.abs( dx ) >= Epsilon || Math.abs( dy ) >= Epsilon || Math.abs( dz ) >= Epsilon || Math.abs( dYaw ) >= Epsilon )
		{
			List<Entity> riders = getRiders();
			
			// apply motion
			prevPosX = posX;
			prevPosY = posY;
			prevPosZ = posZ;
			prevRotationYaw = rotationYaw;
			prevRotationPitch = rotationPitch;
			setRotation(
				rotationYaw + dYaw,
				rotationPitch
			);
			setPosition(
				posX + dx,
				posY + dy,
				posZ + dz
			);
			
			moveRiders( riders, dx, dy, dz, dYaw );
			// TEMP
			//moveCollidingEntities( riders );
			moveWater( waterHeightInBlockSpace );
		}
		
		// did the ship sink?
		if( isSunk( waterHeightInBlockSpace ) )
		{
			// TEMP
			System.out.println( String.format( "%s Ship Sunk!",
				worldObj.isRemote ? "CLIENT" : "SERVER"
			) );
			
			// unlaunch the ship at the bottom of the ocean
			ShipUnlauncher unlauncher = new ShipUnlauncher( this );
			unlauncher.snapToNearestDirection();
			unlauncher.unlaunch();
			return;
		}
		
		// update the world
		m_blocks.updateEntities();
	}
	
	@Override
	// I think this used to be deobfuscated as interact() in an older MCP version
	public boolean func_130002_c( EntityPlayer player )
	{
		// find out what block the player is targeting
		TreeSet<MovingObjectPosition> intersections = m_collider.getBlocksPlayerIsLookingAt( player );
		if( intersections.isEmpty() )
		{
			// TEMP
			System.out.println( String.format( "%s EntityShip.interact(): no hit",
				worldObj.isRemote ? "CLIENT" : "SERVER"
			) );
			
			return false;
		}
		
		// just get the first intersected block (it's the closest one)
		// UNDONE: could optimize this by trying to find the closest block first... but we probably don't care for now
		MovingObjectPosition intersection = intersections.first();
		
		// activate the block
		Block block = Block.blocksList[m_blocks.getBlockId( intersection.blockX, intersection.blockY, intersection.blockZ )];
		
		// TEMP
		System.out.println( String.format( "%s EntityShip.interact(): (%d,%d,%d) %s",
			worldObj.isRemote ? "CLIENT" : "SERVER",
			intersection.blockX, intersection.blockY, intersection.blockZ,
			block.getUnlocalizedName()
		) );
		
		return block.onBlockActivated(
			m_blocks,
			intersection.blockX, intersection.blockY, intersection.blockZ,
			player,
			intersection.sideHit,
			(float)intersection.hitVec.xCoord, (float)intersection.hitVec.yCoord, (float)intersection.hitVec.zCoord
		);
	}
	
	private boolean isSunk( double waterHeight )
	{
		// is the ship completely underwater?
		boolean isUnderwater = waterHeight > m_blocks.getBoundingBox().maxY + 1.5;
		
		// UNDONE: will have to use something smarter for submarines!
		// UNDONE: also un-floodable ships like rafts
		return motionY == 0 && isUnderwater;
	}
	
	public void worldToShip( Vec3 v )
	{
		double x = worldToShipX( v.xCoord, v.zCoord );
		double y = worldToShipY( v.yCoord );
		double z = worldToShipZ( v.xCoord, v.zCoord );
		
		v.xCoord = x;
		v.yCoord = y;
		v.zCoord = z;
	}
	
	public void worldToShipDirection( Vec3 v )
	{
		// just apply the rotation
		float yawRad = (float)Math.toRadians( rotationYaw );
		float cos = MathHelper.cos( yawRad );
		float sin = MathHelper.sin( yawRad );
		double x = v.xCoord*cos - v.zCoord*sin;
		double z = v.xCoord*sin + v.zCoord*cos;
		
		v.xCoord = x;
		v.zCoord = z;
	}
	
	public double worldToShipX( double x, double z )
	{
		float yawRad = (float)Math.toRadians( rotationYaw );
		double cos = MathHelper.cos( yawRad );
		double sin = MathHelper.sin( yawRad );
		return ( x - posX )*cos - ( z - posZ )*sin;
	}
	
	public double worldToShipY( double y )
	{
		return y - posY;
	}
	
	public double worldToShipZ( double x, double z )
	{
		float yawRad = (float)Math.toRadians( rotationYaw );
		double cos = MathHelper.cos( yawRad );
		double sin = MathHelper.sin( yawRad );
		return ( x - posX )*sin + ( z - posZ )*cos;
	}
	
	public void shipToWorld( Vec3 v )
	{
		double x = shipToWorldX( v.xCoord, v.zCoord );
		double y = shipToWorldY( v.yCoord );
		double z = shipToWorldZ( v.xCoord, v.zCoord );
		
		v.xCoord = x;
		v.yCoord = y;
		v.zCoord = z;
	}
	
	public void shipToWorldDirection( Vec3 v )
	{
		// just apply the rotation
		float yawRad = (float)Math.toRadians( rotationYaw );
		float cos = MathHelper.cos( yawRad );
		float sin = MathHelper.sin( yawRad );
		double x = v.xCoord*cos + v.zCoord*sin;
		double z = -v.xCoord*sin + v.zCoord*cos;
		
		v.xCoord = x;
		v.zCoord = z;
	}
	
	public double shipToWorldX( double x, double z )
	{
		float yawRad = (float)Math.toRadians( rotationYaw );
		double cos = MathHelper.cos( yawRad );
		double sin = MathHelper.sin( yawRad );
		return x*cos + z*sin + posX;
	}
	
	public double shipToWorldY( double y )
	{
		return y + posY;
	}
	
	public double shipToWorldZ( double x, double z )
	{
		float yawRad = (float)Math.toRadians( rotationYaw );
		double cos = MathHelper.cos( yawRad );
		double sin = MathHelper.sin( yawRad );
		return -x*sin + z*cos + posZ;
	}
	
	public void shipToBlocks( Vec3 v )
	{
		v.xCoord = shipToBlocksX( v.xCoord );
		v.yCoord = shipToBlocksY( v.yCoord );
		v.zCoord = shipToBlocksZ( v.zCoord );
	}
	
	public double shipToBlocksX( double x )
	{
		return x - m_shipBlockX;
	}
	
	public double shipToBlocksY( double y )
	{
		return y - m_shipBlockY;
	}
	
	public double shipToBlocksZ( double z )
	{
		return z - m_shipBlockZ;
	}
	
	public void blocksToShip( Vec3 v )
	{
		v.xCoord = blocksToShipX( v.xCoord );
		v.yCoord = blocksToShipY( v.yCoord );
		v.zCoord = blocksToShipZ( v.zCoord );
	}
	
	public double blocksToShipX( double x )
	{
		return x + m_shipBlockX;
	}
	
	public double blocksToShipY( double y )
	{
		return y + m_shipBlockY;
	}
	
	public double blocksToShipZ( double z )
	{
		return z + m_shipBlockZ;
	}
	
	public RotatedBB worldToBlocks( AxisAlignedBB box )
	{
		// transform the box center into block space
		Vec3 center = Vec3.createVectorHelper(
			( box.minX + box.maxX )/2,
			( box.minY + box.maxY )/2,
			( box.minZ + box.maxZ )/2
		);
		worldToShip( center );
		shipToBlocks( center );
		
		// build a box of the same dimensions in blocks space
		double dxh = ( box.maxX - box.minX )/2;
		double dyh = ( box.maxY - box.minY )/2;
		double dzh = ( box.maxZ - box.minZ )/2;
		box = AxisAlignedBB.getBoundingBox(
			center.xCoord - dxh, center.yCoord - dyh, center.zCoord - dzh,
			center.xCoord + dxh, center.yCoord + dyh, center.zCoord + dzh
		);
		
		return new RotatedBB( box, -rotationYaw );
	}
	
	public RotatedBB blocksToWorld( AxisAlignedBB box )
	{
		// transform the box center into world space
		Vec3 center = Vec3.createVectorHelper(
			( box.minX + box.maxX )/2,
			( box.minY + box.maxY )/2,
			( box.minZ + box.maxZ )/2
		);
		blocksToShip( center );
		shipToWorld( center );
		
		// build a box of the same dimensions in world space
		double dxh = ( box.maxX - box.minX )/2;
		double dyh = ( box.maxY - box.minY )/2;
		double dzh = ( box.maxZ - box.minZ )/2;
		box = AxisAlignedBB.getBoundingBox(
			center.xCoord - dxh, center.yCoord - dyh, center.zCoord - dzh,
			center.xCoord + dxh, center.yCoord + dyh, center.zCoord + dzh
		);
		
		return new RotatedBB( box, rotationYaw );
	}
	
	private void adjustMotionDueToGravityAndBuoyancy( double waterHeightInBlockSpace )
	{
		/* only simulate buoyancy if we're outside of the epsilon for the equilibrium y pos
		final double EquilibriumWaterHeightEpsilon = 0.05;
		double distToEquilibrium = waterHeightInBlockSpace - m_physics.getEquilibriumWaterHeight();
		if( Math.abs( distToEquilibrium ) > EquilibriumWaterHeightEpsilon )
		{
		*/
		
		Vec3 velocity = Vec3.createVectorHelper( 0, motionY, 0 );
		
		double accelerationDueToBouyancy = m_physics.getNetUpAcceleration( waterHeightInBlockSpace );
		double accelerationDueToDrag = m_physics.getLinearAccelerationDueToDrag( velocity, waterHeightInBlockSpace, m_blocks.getGeometry().getEnvelopes() );
		
		// make sure drag acceleration doesn't reverse the velocity!
		// NOTE: drag is always positive right now. We'll fix the sign later
		accelerationDueToDrag = Math.min( Math.abs( motionY + accelerationDueToBouyancy ), accelerationDueToDrag );
		
		// make sure drag opposes velocity
		if( Math.signum( accelerationDueToDrag ) == Math.signum( motionY ) )
		{
			accelerationDueToDrag *= -1;
		}
		
		/* TEMP
		double netAcceleration = accelerationDueToBouyancy + accelerationDueToDrag;
		System.out.println( String.format( "%s velocity: %.4f, bouyancy: %.4f, drag: %.4f, net: %.4f",
			worldObj.isRemote ? "CLIENT" : "SERVER",
			motionY, accelerationDueToBouyancy, accelerationDueToDrag, netAcceleration
		) );
		*/
		
		motionY += accelerationDueToBouyancy + accelerationDueToDrag;
	}
	
	private void adjustMotionDueToThrustAndDrag( double waterHeightInBlockSpace )
	{
		// process pilot actions
		PilotAction.resetShip( this, m_pilotActions, m_oldPilotActions );
		PilotAction.applyToShip( this, m_pilotActions );
		m_oldPilotActions = m_pilotActions;
		
		// clamp the throttle
		if( linearThrottle < LinearThrottleMin )
		{
			linearThrottle = LinearThrottleMin;
		}
		if( linearThrottle > LinearThrottleMax )
		{
			linearThrottle = LinearThrottleMax;
		}
		
		// get the velocity direction
		double velocityDirX = motionX;
		double velocityDirZ = motionZ;
		double speed = Math.sqrt( velocityDirX*velocityDirX + velocityDirZ*velocityDirZ );
		if( speed > 0 )
		{
			velocityDirX /= speed;
			velocityDirZ /= speed;
		}
		
		// get the velocity in block coords
		Vec3 velocityInBlockCoords = Vec3.createVectorHelper( motionX, 0, motionZ );
		worldToShipDirection( velocityInBlockCoords );
		
		// apply the drag
		double linearAccelerationDueToDrag = m_physics.getLinearAccelerationDueToDrag( velocityInBlockCoords, waterHeightInBlockSpace, m_blocks.getGeometry().getEnvelopes() );
		// make sure drag acceleration doesn't reverse the velocity!
		linearAccelerationDueToDrag = Math.min( speed, linearAccelerationDueToDrag );
		motionX += -velocityDirX*linearAccelerationDueToDrag;
		motionZ += -velocityDirZ*linearAccelerationDueToDrag;
		
		if( m_sideShipForward != null )
		{
			if( m_sendPilotChangesToServer )
			{
				// send a packet to the server
				PacketPilotShip packet = new PacketPilotShip( entityId, m_pilotActions, m_sideShipForward, linearThrottle, angularThrottle );
				PacketDispatcher.sendPacketToServer( packet.getCustomPacket() );
				m_sendPilotChangesToServer = false;
			}
			
			// compute the forward vector
			float yawRad = (float)Math.toRadians( rotationYaw );
			float cos = MathHelper.cos( yawRad );
			float sin = MathHelper.sin( yawRad );
			double forwardX = m_sideShipForward.getDx()*cos + m_sideShipForward.getDz()*sin;
			double forwardZ = -m_sideShipForward.getDx()*sin + m_sideShipForward.getDz()*cos;
			
			// apply the thrust
			double linearAccelerationDueToThrust = m_physics.getLinearAccelerationDueToThrust( m_propulsion )*linearThrottle/LinearThrottleMax;
			motionX += forwardX*linearAccelerationDueToThrust;
			motionZ += forwardZ*linearAccelerationDueToThrust;
			
			/* TEMP
			System.out.println( String.format( "%s speed: %.4f, thrust: %.4f, drag: %.4f",
				worldObj.isRemote ? "CLIENT" : "SERVER",
				speed, linearAccelerationDueToThrust, linearAccelerationDueToDrag
			) );
			*/
		}
		
		// get the angular acceleration
		double angularAccelerationDueToThrust = m_physics.getAngularAccelerationDueToThrust( m_propulsion )*angularThrottle/AngularThrottleMax;
		double angularAccelerationDueToDrag = m_physics.getAngularAccelerationDueToDrag( motionYaw );
		
		// make sure drag acceleration doesn't reverse the velocity!
		angularAccelerationDueToDrag = Math.min( Math.abs( motionYaw ), angularAccelerationDueToDrag );
		
		// make sure the drag is opposed to the velocity
		if( Math.signum( angularAccelerationDueToDrag ) == Math.signum( motionYaw ) )
		{
			angularAccelerationDueToDrag *= -1;
		}
		
		/* TEMP
		System.out.println( String.format( "%s speed: %.4f, thrust: %.4f, drag: %.4f",
			worldObj.isRemote ? "CLIENT" : "SERVER",
			speed, angularAccelerationDueToThrust, angularAccelerationDueToDrag
		) );
		*/
		
		// apply the angular acceleration
		motionYaw += angularAccelerationDueToThrust + angularAccelerationDueToDrag;
	}
	
	private void adjustMotionDueToBlockCollisions( )
	{
		// UNDONE: we can probably optimize this quite a bit
		
		// where would we move to?
		AxisAlignedBB nextBox = AxisAlignedBB.getBoundingBox( 0, 0, 0, 0, 0, 0 );
		computeBoundingBox( nextBox, posX + motionX, posY + motionY, posZ + motionZ, rotationYaw + motionYaw );
		
		// do a range query to get colliding world blocks
		List<AxisAlignedBB> nearbyWorldBlocks = new ArrayList<AxisAlignedBB>();
        int minX = MathHelper.floor_double( nextBox.minX );
        int maxX = MathHelper.floor_double( nextBox.maxX );
        int minY = MathHelper.floor_double( nextBox.minY );
        int maxY = MathHelper.floor_double( nextBox.maxY );
        int minZ = MathHelper.floor_double( nextBox.minZ );
        int maxZ = MathHelper.floor_double( nextBox.maxZ );
        for( int x=minX; x<=maxX; x++ )
        {
            for( int z=minZ; z<=maxZ; z++ )
            {
                for( int y=minY; y<=maxY; y++ )
                {
                    Block block = Block.blocksList[worldObj.getBlockId( x, y, z )];
                    if( block != null )
                    {
                        block.addCollisionBoxesToList( worldObj, x, y, z, nextBox, nearbyWorldBlocks, this );
                    }
                }
            }
        }
        
        // no collisions?
 		if( nearbyWorldBlocks.isEmpty() )
 		{
 			return;
 		}
 		
 		// compute the scaling to avoid the collision
 		// it's ok if the ship block doesn't actually collide with every nearby world block
 		// in that case, the scaling will be > 1 and will be ignored
 		double s = 1.0;
 		Vec3 p = Vec3.createVectorHelper( 0, 0, 0 );
 		AxisAlignedBB updatedShipBlock = AxisAlignedBB.getBoundingBox( 0, 0, 0, 0, 0, 0 );
 		int numCollisions = 0;
		for( EntityShipBlock blockEntity : m_blockEntitiesArray )
        {
			// get the next position of the ship block
			blockEntity.getBlockPosition( p );
			// HACKHACK: temporarily adjust the ship's yaw to fool shipToWorld()
			rotationYaw += motionYaw;
			blocksToShip( p );
			shipToWorld( p );
			p.xCoord += motionX;
			p.yCoord += motionY;
			p.zCoord += motionZ;
			EntityShipBlock.computeBoundingBox( updatedShipBlock, p.xCoord, p.yCoord, p.zCoord, rotationYaw );
			rotationYaw -= motionYaw;
			
			for( AxisAlignedBB worldBlock : nearbyWorldBlocks )
			{
				// would the boxes actually collide?
				if( !worldBlock.intersectsWith( updatedShipBlock ) )
				{
					continue;
				}
				
				numCollisions++;
				s = Math.min( s, getScalingToAvoidCollision( motionX, motionY, motionZ, blockEntity.getBoundingBox(), worldBlock ) );
			}
        }
		
		// avoid the collision
		motionX *= s;
		motionY *= s;
		motionZ *= s;
		
		// update rotation too
		if( numCollisions > 0 )
		{
			motionYaw = 0;
		}
	}
	
	private double getScalingToAvoidCollision( double dx, double dy, double dz, AxisAlignedBB shipBox, AxisAlignedBB externalBox )
	{
		double sx = 0;
		if( dx > 0 )
		{
			sx = ( externalBox.minX - shipBox.maxX )/dx;
		}
		else if( dx < 0 )
		{
			sx = ( externalBox.maxX - shipBox.minX )/dx;
		}
		
		double sy = 0;
		if( dy > 0 )
		{
			sy = ( externalBox.minY - shipBox.maxY )/dy;
		}
		else if( dy < 0 )
		{
			sy = ( externalBox.maxY - shipBox.minY )/dy;
		}
		
		double sz = 0;
		if( dz > 0 )
		{
			sz = ( externalBox.minZ - shipBox.maxZ )/dz;
		}
		else if( dz < 0 )
		{
			sz = ( externalBox.maxZ - shipBox.minZ )/dz;
		}
		
		double s = Math.max( sx, Math.max( sy, sz ) );
		
		// if the scaling we get is below zero, there was no real collision to worry about
		if( s < 0 )
		{
			return 1.0;
		}
		return s;
	}
	
	public List<Entity> getRiders( )
	{
		// UNDONE: cache this. It only needs to be updated every tick
		
		final double Expand = 0.5;
		AxisAlignedBB checkBox = AxisAlignedBB.getAABBPool().getAABB(
			boundingBox.minX - Expand,
			boundingBox.minY - Expand,
			boundingBox.minZ - Expand,
			boundingBox.maxX + Expand,
			boundingBox.maxY + Expand,
			boundingBox.maxZ + Expand
		);
		@SuppressWarnings( "unchecked" )
		List<Entity> entities = worldObj.getEntitiesWithinAABB( Entity.class, checkBox );
		
		// remove any entities from the list not close enough to be considered riding
		// also remove entities that are floating or moving upwards (e.g. jumping)
		Iterator<Entity> iter = entities.iterator();
		while( iter.hasNext() )
		{
			Entity entity = iter.next();
			if( entity == this || entity.motionY >= 0 || !isEntityCloseEnoughToRide( entity ) )
			{
				iter.remove();
			}
		}
		
		return entities;
	}
	
	public boolean isEntityCloseEnoughToRide( Entity entity )
	{
		// get the closest block y the entity could be standing on
		int y = (int)( shipToBlocksY( worldToShipY( entity.boundingBox.minY ) ) + 0.5 ) - 1;
		
		// convert the entity box into block coordinates
		RotatedBB box = worldToBlocks( entity.boundingBox );
		
		for( ChunkCoordinates coords : m_blocks.getGeometry().xzRangeQuery( y, box ) )
		{
			if( isBoxCloseEnoughToRide( box, coords ) )
			{
				return true;
			}
		}
		
		return false;
	}
	
	private boolean isBoxCloseEnoughToRide( RotatedBB box, ChunkCoordinates coords )
	{
		// is the entity close enough to the top of the block?
		double yBlockTop = coords.posY + 1;
		return Math.abs( yBlockTop - box.getMinY() ) <= RiderEpsilon;
	}
	
	private void moveRiders( List<Entity> riders, double dx, double dy, double dz, float dYaw )
	{
		Vec3 p = Vec3.createVectorHelper( 0, 0, 0 );
		
		// move riders
		for( Entity rider : riders )
		{
			// apply rotation of position relative to the ship center
			p.xCoord = rider.posX + dx;
			p.yCoord = rider.posY + dy;
			p.zCoord = rider.posZ + dz;
			worldToShip( p );
			float yawRad = (float)Math.toRadians( dYaw );
			float cos = MathHelper.cos( yawRad );
			float sin = MathHelper.sin( yawRad );
			double x = p.xCoord*cos + p.zCoord*sin;
			double z = -p.xCoord*sin + p.zCoord*cos;
			p.xCoord = x;
			p.zCoord = z;
			shipToWorld( p );
			dx += ( p.xCoord - dx ) - rider.posX;
			dz += ( p.zCoord - dz ) - rider.posZ;
			
			/* adjust the translation to snap riders to the surface of the ship
			double riderY = shipToBlocksY( worldToShipY( rider.boundingBox.minY ) );
			int targetY = (int)( riderY + 0.5 );
			dy += targetY - riderY;
			*/
			
			// apply the transformation
			rider.rotationYaw -= dYaw;
			rider.setPosition(
				rider.posX + dx,
				rider.posY + dy,
				rider.posZ + dz
			);
		}
	}
	
	private void moveCollidingEntities( List<Entity> riders )
	{
		@SuppressWarnings( "unchecked" )
		List<Entity> entities = worldObj.getEntitiesWithinAABB( Entity.class, boundingBox );
		
		for( Entity entity : entities )
		{
			// don't collide with self
			if( entity == this )
			{
				continue;
			}
			
			// is this entity is a rider?
			// UNDONE: could use more efficient check
			boolean isRider = riders.contains( entity );
			
			moveCollidingEntity( entity, isRider );
		}
	}
	
	private void moveCollidingEntity( Entity entity, boolean isRider )
	{
		// UNDONE: collisions isn't quite perfect yet.
		// The displacements aren't quite the right size for the observed z-overlap!
		
		// find the displacement that moves the entity out of the way of the blocks
		double maxDisplacement = 0.0;
		double maxDx = 0;
		double maxDy = 0;
		double maxDz = 0;
		
		// UNDONE: optimize this range query. Do something smarter than brute force
		for( EntityShipBlock blockEntity : m_blockEntitiesArray )
		{
			// is this block actually colliding with the entity?
			if( !blockEntity.getBoundingBox().intersectsWith( entity.boundingBox ) )
			{
				continue;
			}
			
			// get the actual motion vector of the block accounting for rotation
			double dxBlock = blockEntity.posX - blockEntity.prevPosX;
			double dyBlock = blockEntity.posY - blockEntity.prevPosY;
			double dzBlock = blockEntity.posZ - blockEntity.prevPosZ;
			
			// is the ship block actually moving towards the entity?
			Vec3 toEntity = Vec3.createVectorHelper(
				entity.posX - blockEntity.posX,
				entity.posY - blockEntity.posY,
				entity.posZ - blockEntity.posZ
			);
			Vec3 motion = Vec3.createVectorHelper( dxBlock, dyBlock, dzBlock );
			boolean isMovingTowardsEntity = toEntity.dotProduct( motion ) > 0.0;
			if( !isMovingTowardsEntity )
			{
				continue;
			}
			
			double scaling = getScalingToPushBox( dxBlock, dyBlock, dzBlock, blockEntity.getBoundingBox(), entity.boundingBox );
			
			// calculate the displacement
			double displacement = scaling*Math.sqrt( dxBlock*dxBlock + dyBlock*dyBlock + dzBlock*dzBlock );
			
			if( displacement > maxDisplacement )
			{
				maxDisplacement = displacement;
				maxDx = scaling*dxBlock;
				maxDy = scaling*dyBlock;
				maxDz = scaling*dzBlock;
			}
			
			// TEMP
			System.out.println( String.format(
				"%s entity %s displcement for block: %.4f (%.2f,%.2f,%.2f), z overlap: %.4f",
				worldObj.isRemote ? "CLIENT" : "SERVER",
				entity.getClass().getSimpleName(), displacement,
				scaling*dxBlock, scaling*dyBlock, scaling*dzBlock,
				Math.min( blockEntity.getBoundingBox().maxZ - entity.boundingBox.minZ, entity.boundingBox.maxZ - blockEntity.getBoundingBox().minZ )
			) );
		}
		
		// don't update y-positions for riders
		if( isRider )
		{
			maxDy = 0;
		}
		
		// move the entity out of the way of the blocks
		entity.setPosition(
			entity.posX + maxDx,
			entity.posY + maxDy,
			entity.posZ + maxDz
		);
		// UNDONE: change to moveEntity() and get rid of rider snap?
	}
	
	private double getScalingToPushBox( double dx, double dy, double dz, AxisAlignedBB shipBox, AxisAlignedBB externalBox )
	{
		double sx = 0;
		if( dx > 0 )
		{
			sx = ( shipBox.maxX - externalBox.minX )/dx;
		}
		else if( dx < 0 )
		{
			sx = ( shipBox.minX - externalBox.maxX )/dx;
		}
		
		double sy = 0;
		if( dy > 0 )
		{
			sy = ( shipBox.maxY - externalBox.minY )/dy;
		}
		else if( dy < 0 )
		{
			sy = ( shipBox.minY - externalBox.maxY )/dy;
		}
		
		double sz = 0;
		if( dz > 0 )
		{
			sz = ( shipBox.maxZ - externalBox.minZ )/dz;
		}
		else if( dz < 0 )
		{
			sz = ( shipBox.minZ - externalBox.maxZ )/dz;
		}
		
		// clamp scalings to <= 1
		return Math.min( 1, Math.max( sx, Math.max( sy, sz ) ) );
	}
	
	private void moveWater( double waterHeightBlocks )
	{
		// get all the trapped air blocks
		int surfaceLevelBlocks = MathHelper.floor_double( waterHeightBlocks );
		TreeSet<ChunkCoordinates> trappedAirBlocks = m_blocks.getGeometry().getTrappedAir( surfaceLevelBlocks );
		if( trappedAirBlocks.isEmpty() )
		{
			// the ship is out of the water
			return;
		}
		
		int surfaceLevelWorld = getWaterHeight();
		
		// find the world water blocks that intersect the trapped air blocks
		TreeSet<ChunkCoordinates> displacedWaterBlocks = new TreeSet<ChunkCoordinates>();
		AxisAlignedBB box = AxisAlignedBB.getBoundingBox( 0, 0, 0, 0, 0, 0 );
		Vec3 p = Vec3.createVectorHelper( 0, 0, 0 );
		for( ChunkCoordinates coords : trappedAirBlocks )
		{
			// compute the bounding box for the air block
			p.xCoord = coords.posX + 0.5;
			p.yCoord = coords.posY + 0.5;
			p.zCoord = coords.posZ + 0.5;
			blocksToShip( p );
			shipToWorld( p );
			
			m_collider.getBlockWorldBoundingBox( box, coords );
			
			// grow the bounding box just a bit so we get more robust collisions
			final double Delta = 0.1;
			box.expand( Delta, Delta, Delta );
			
			// query for all the world water blocks that intersect it
			int minX = MathHelper.floor_double( box.minX );
			int maxX = MathHelper.floor_double( box.maxX );
			int minY = MathHelper.floor_double( box.minY );
			int maxY = Math.min( MathHelper.floor_double( box.maxY ), surfaceLevelWorld - 1 );
			int minZ = MathHelper.floor_double( box.minZ );
			int maxZ = MathHelper.floor_double( box.maxZ );
			for( int x=minX; x<=maxX; x++ )
			{
				for( int z=minZ; z<=maxZ; z++ )
				{
					for( int y=minY; y<=maxY; y++ )
					{
						Material material = worldObj.getBlockMaterial( x, y, z );
						if( material == Material.water || material == Material.air || material == Ships.m_materialAirWall )
						{
							displacedWaterBlocks.add( new ChunkCoordinates( x, y, z ) );
						}
					}
				}
			}
		}
		
		// which are new blocks to displace?
		for( ChunkCoordinates coords : displacedWaterBlocks )
		{
			if( m_previouslyDisplacedWaterBlocks == null || !m_previouslyDisplacedWaterBlocks.contains( coords ) )
			{
				worldObj.setBlock( coords.posX, coords.posY, coords.posZ, Ships.m_blockAirWall.blockID );
			}
		}
		
		// which blocks are no longer displaced?
		if( m_previouslyDisplacedWaterBlocks != null )
		{
			for( ChunkCoordinates coords : m_previouslyDisplacedWaterBlocks )
			{
				if( !displacedWaterBlocks.contains( coords ) )
				{
					worldObj.setBlock( coords.posX, coords.posY, coords.posZ, Block.waterStill.blockID );
					
					// UNDONE: can get the fill effect back by only turning the surface level into air?
					// or make a special wake block that will self-convert back to water
				}
			}
		}
		
		m_previouslyDisplacedWaterBlocks = displacedWaterBlocks;
	}
	
	private void computeBoundingBox( AxisAlignedBB box, double x, double y, double z, float yaw )
	{
		if( m_blocks == null )
		{
			return;
		}
		
		// make an un-rotated box in world-space
		box.minX = x + blocksToShipX( m_blocks.getBoundingBox().minX );
		box.minY = y + blocksToShipY( m_blocks.getBoundingBox().minY );
		box.minZ = z + blocksToShipZ( m_blocks.getBoundingBox().minZ );
		box.maxX = x + blocksToShipX( m_blocks.getBoundingBox().maxX + 1 );
		box.maxY = y + blocksToShipY( m_blocks.getBoundingBox().maxY + 1 );
		box.maxZ = z + blocksToShipZ( m_blocks.getBoundingBox().maxZ + 1 );
		
		// now rotate by the yaw
		// UNDONE: optimize out the new
		RotatedBB rotatedBox = new RotatedBB( box.copy(), yaw, x, z );
		
		// compute the new xz bounds
		box.minX = Integer.MAX_VALUE;
		box.maxX = Integer.MIN_VALUE;
		box.minZ = Integer.MAX_VALUE;
		box.maxZ = Integer.MIN_VALUE;
		Vec3 p = Vec3.createVectorHelper( 0, 0, 0 );
		for( BoxCorner corner : BlockSide.Top.getCorners() )
		{
			rotatedBox.getCorner( p, corner );
			
			box.minX = Math.min( box.minX, p.xCoord );
			box.maxX = Math.max( box.maxX, p.xCoord );
			box.minZ = Math.min( box.minZ, p.zCoord );
			box.maxZ = Math.max( box.maxZ, p.zCoord );
		}
	}
	
	public void setPilotActions( int actions, BlockSide sideShipForward, boolean sendPilotChangesToServer )
	{
		m_pilotActions = actions;
		m_sideShipForward = sideShipForward;
		m_sendPilotChangesToServer = sendPilotChangesToServer;
	}
}
