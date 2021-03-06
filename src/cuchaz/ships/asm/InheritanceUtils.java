/*******************************************************************************
 * Copyright (c) 2013 jeff.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     jeff - initial API and implementation
 ******************************************************************************/
package cuchaz.ships.asm;

import org.objectweb.asm.ClassReader;

public class InheritanceUtils
{
	private static String[] leafPackages = { "java/", "javax/" };
	
	public static boolean extendsClass( String className, String targetClassName )
	{
		// base case
		if( className.equalsIgnoreCase( targetClassName ) )
		{
			return true;
		}
		else if( isLeafPackage( className ) )
		{
			return false;
		}
		
		// is this class an array? Just ignore arrays
		if( className.startsWith( "[" ) )
		{
			return false;
		}
		
		// load the super class and test recursively
		try
		{
			ClassReader classReader = new ClassReader( className.replace( '.', '/' ) );
			String superClassName = classReader.getSuperName();
			if( superClassName != null )
			{
				return extendsClass( superClassName, targetClassName );
			}
		}
		catch( Exception ex )
		{
			// NOTE: using the logger here causes class loading circles. Need to use stdout
			System.out.println( "Unable to read class: " + className + ". Assuming it's not a " + targetClassName );
			ex.printStackTrace( System.out );
		}
		
		return false;
	}
	
	public static boolean implementsInterface( String interfaceName, String targetInterfaceName )
	{
		// base case
		if( interfaceName.equalsIgnoreCase( targetInterfaceName ) )
		{
			return true;
		}
		else if( isLeafPackage( interfaceName ) )
		{
			return false;
		}
		
		// recurse
		String className = interfaceName.replace( '.', '/' );
		try
		{
			ClassReader classReader = new ClassReader( className );
			for( String i : classReader.getInterfaces() )
			{
				if( implementsInterface( i, targetInterfaceName ) )
				{
					return true;
				}
			}
		}
		catch( Exception ex )
		{
			// NOTE: using the logger here causes class loading circles. Need to use stdout
			System.out.println( "Unable to read class: " + className + ". Assuming it's not a " + targetInterfaceName );
			ex.printStackTrace( System.out );
		}
		
		return false;
	}
	
	private static boolean isLeafPackage( String name )
	{
		for( String prefix : leafPackages )
		{
			if( name.startsWith( prefix ) )
			{
				return true;
			}
		}
		return false;
	}
}
