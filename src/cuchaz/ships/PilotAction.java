package cuchaz.ships;

import java.util.List;

import net.minecraft.client.settings.GameSettings;

import org.lwjgl.input.Keyboard;

public enum PilotAction
{
	Forward
	{
		@Override
		public void applyToShip( EntityShip ship )
		{
			ship.linearThrottle = 1;
		}
		
		@Override
		public void resetShip( EntityShip ship )
		{
			ship.linearThrottle = 0;
		}
	},
	Backward
	{
		@Override
		public void applyToShip( EntityShip ship )
		{
			ship.linearThrottle = -1;
		}
		
		@Override
		public void resetShip( EntityShip ship )
		{
			ship.linearThrottle = 0;
		}
	},
	Left
	{
		@Override
		public void applyToShip( EntityShip ship )
		{
			ship.angularThrottle = 1;
		}

		@Override
		public void resetShip( EntityShip ship )
		{
			ship.angularThrottle = 0;
		}
	},
	Right
	{
		@Override
		public void applyToShip( EntityShip ship )
		{
			ship.angularThrottle = -1;
		}
		
		@Override
		public void resetShip( EntityShip ship )
		{
			ship.angularThrottle = 0;
		}
	},
	ThrottleUp
	{
		@Override
		public void applyToShip( EntityShip ship )
		{
			// UNDONE: make the throttle "sticky" around 0
			ship.linearThrottle += 0.01;
		}
	},
	ThrottleDown
	{
		@Override
		public void applyToShip( EntityShip ship )
		{
			ship.linearThrottle -= 0.01;
		}
	};
	
	private int m_keyCode;
	
	private PilotAction( )
	{
		m_keyCode = -1;
	}
	
	public static void setActionCodes( GameSettings settings )
	{
		Forward.m_keyCode = settings.keyBindForward.keyCode;
		Backward.m_keyCode = settings.keyBindBack.keyCode;
		Left.m_keyCode = settings.keyBindLeft.keyCode;
		Right.m_keyCode = settings.keyBindRight.keyCode;
		ThrottleUp.m_keyCode = settings.keyBindForward.keyCode;
		ThrottleDown.m_keyCode = settings.keyBindBack.keyCode;
	}
	
	public static int getActiveActions( GameSettings settings, List<PilotAction> allowedActions )
	{
		// roll up the actions into a bit vector
		int actions = 0;
		for( PilotAction action : allowedActions )
		{
			if( Keyboard.isKeyDown( action.m_keyCode ) )
			{
				actions |= 1 << action.ordinal();
			}
		}
		return actions;
	}
	
	public static void applyToShip( EntityShip ship, int actions )
	{
		for( PilotAction action : values() )
		{
			if( action.isActive( actions ) )
			{
				action.applyToShip( ship );
			}
		}
	}
	
	public static void resetShip( EntityShip ship, int actions, int oldActions )
	{
		for( PilotAction action : values() )
		{
			if( action.isActive( oldActions ) && !action.isActive( actions ) )
			{
				action.resetShip( ship );
			}
		}
	}
	
	public boolean isActive( int actions )
	{
		return ( ( actions >> ordinal() ) & 0x1 ) == 1;
	}
	
	protected abstract void applyToShip( EntityShip ship );
	
	protected void resetShip( EntityShip ship )
	{
		// do nothing
	}
}
