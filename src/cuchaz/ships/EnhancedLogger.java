package cuchaz.ships;

import java.util.logging.Level;
import java.util.logging.Logger;

import cuchaz.modsShared.Environment;

public class EnhancedLogger
{
	private Logger m_logger;
	
	public EnhancedLogger( Logger logger )
	{
		m_logger = logger;
	}
	
	public void log( Level level, String message, Throwable t )
	{
		// prepend the side name
		m_logger.log( level, Environment.getSide().name().toUpperCase() + " " + message, t );
	}
	
	public void log( Level level, String message )
	{
		// prepend the side name
		m_logger.log( level, Environment.getSide().name().toUpperCase() + " " + message );
	}
	
	// convenience formatter methods
	
	public void warning( String message, Object ... args )
	{
		log( Level.WARNING, String.format( message, args ) );
	}
	
	public void warning( Throwable t, String message, Object ... args )
	{
		log( Level.WARNING, String.format( message, args ), t );
	}
	
	public void info( String message, Object ... args )
	{
		log( Level.INFO, String.format( message, args ) );
	}
	
	public void info( Throwable t, String message, Object ... args )
	{
		log( Level.INFO, String.format( message, args ), t );
	}
	
	public void fine( String message, Object ... args )
	{
		log( Level.FINE, String.format( message, args ) );
	}
}
