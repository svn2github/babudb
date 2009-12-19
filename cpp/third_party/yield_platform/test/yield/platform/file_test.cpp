// Copyright 2003-2009 Minor Gordon, with original implementations and ideas contributed by Felix Hupfeld.
// This source comes from the Yield project. It is licensed under the GPLv2 (see COPYING for terms and conditions).

#include "yield/platform/yunit.h"
#include "yield/platform/platform_exception.h"
#include "yield/platform/file.h"
using namespace YIELD;


#define TEST_FILE_NAME "File_test.txt"


TEST_SUITE( File )

TEST( FileConstructors, File )
{
	try
	{
		{
			File f( TEST_FILE_NAME, O_CREAT|O_TRUNC|O_WRONLY|O_CLOSE_ON_DESTRUCT );
		}
		
		File* f = File::open( TEST_FILE_NAME );
		ASSERT_NOTEQUAL( f, NULL );
		delete f;

		{
			fd_t fd = DiskOperations::open( TEST_FILE_NAME );
			File f( fd );
		}
	}
	catch ( PlatformException& exc )
	{
		try { DiskOperations::unlink( TEST_FILE_NAME ); } catch ( ... ) { }
		throw exc;
	}
};

TEST( FileReadWrite, File )
{
	try
	{
		{
			File f( TEST_FILE_NAME, O_CREAT|O_TRUNC|O_WRONLY|O_CLOSE_ON_DESTRUCT );
			f.write( "hello" );
		}

		char hello[6]; hello[5] = 0;
		{
			File f( ( const char* )TEST_FILE_NAME );
			ssize_t read_ret = f.read( hello, 5 );
			ASSERT_EQUAL( read_ret, 5 );
			ASSERT_TRUE( strcmp( hello, "hello" ) == 0 );
		}
	}
	catch ( PlatformException& exc )
	{
		try { DiskOperations::unlink( TEST_FILE_NAME ); } catch ( ... ) { }
		throw exc;
	}
};


TEST_MAIN( File )

