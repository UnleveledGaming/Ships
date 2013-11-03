package cuchaz.ships.packets;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import cuchaz.ships.EntityShip;
import cuchaz.ships.ShipWorld;
import cuchaz.ships.Ships;

public class PacketShipBlocks extends Packet
{
	public static final String Channel = "shipBlocks";
	
	private int m_entityId;
	private byte[] m_blocksData;
	
	public PacketShipBlocks( )
	{
		super( Channel );
	}
	
	public PacketShipBlocks( EntityShip ship )
	{
		this();
		
		m_entityId = ship.entityId;
		m_blocksData = ship.getBlocks().getData();
	}
	
	@Override
	public void writeData( DataOutputStream out )
	throws IOException
	{
		out.writeInt( m_entityId );
		out.writeInt( m_blocksData.length );
		out.write( m_blocksData );
	}
	
	@Override
	public void readData( DataInputStream in )
	throws IOException
	{
		m_entityId = in.readInt();
		m_blocksData = new byte[in.readInt()]; // this is potentially risky?
		in.read( m_blocksData );
	}
	
	@Override
	public void onPacketReceived( EntityPlayer player )
	{
		// get the ship
		Entity entity = player.worldObj.getEntityByID( m_entityId );
		if( entity == null || !( entity instanceof EntityShip ) )
		{
			Ships.logger.warning( String.format( "Client dropping PacketShipBlocks for client ship %d! Can't find the ship!", m_entityId ) );
			return;
		}
		EntityShip ship = (EntityShip)entity;
		
		// send the block data
		ship.setBlocks( new ShipWorld( player.worldObj, m_blocksData ) );
	}
}
