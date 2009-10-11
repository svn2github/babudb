// This file is part of babudb/cpp
//
// Copyright (c) 2008, Felix Hupfeld, Jan Stender, Bjoern Kolbeck, Mikael Hoegqvist, Zuse Institute Berlin.
// Copyright (c) 2009, Felix Hupfeld
// Licensed under the BSD License, see LICENSE file for details.
//
// Author: Felix Hupfeld (felix@storagebox.org)

#include "babudb/Database.h"
#include <vector>
#include <utility>

#include "babudb/profiles/string_key.h"
#include "babudb/test.h"
#include "index/merger.h"

#include "yield/platform/memory_mapped_file.h"
using YIELD::MemoryMappedFile;
using namespace babudb;

TEST_TMPDIR(Database,babudb)
{
  StringOrder myorder;
  std::vector<babudb::IndexDescriptor> indices;
  indices.push_back(std::make_pair("testidx", &myorder));
  Database* db = Database::Open(testPath("test").getHostCharsetPath(), indices);

  StringSetOperation("testidx", 1, "Key1","data1").applyTo(*db);
  StringSetOperation("testidx", 2, "Key2","data2").applyTo(*db);

  EXPECT_FALSE(db->Lookup("testidx",DataHolder("Key1")).isEmpty());

  StringSetOperation("testidx", 3, "Key1").applyTo(*db);

  EXPECT_TRUE(db->Lookup("testidx",DataHolder("Key1")).isEmpty());

  delete db;
}

TEST_TMPDIR(Database_Migration,babudb)
{
  StringOrder myorder;

  IndexMerger* merger = new IndexMerger(myorder);
  merger->Add(1, DataHolder("Key1"), DataHolder("data1"));
  merger->Add(2, DataHolder("Key2"), DataHolder("data2"));
  merger->Setup(testPath("test-testidx").getHostCharsetPath());
  merger->Run();
  delete merger;

  std::vector<babudb::IndexDescriptor> indices;
  indices.push_back(std::make_pair("testidx", &myorder));
  Database* db = Database::Open(testPath("test").getHostCharsetPath(), indices);

  EXPECT_EQUAL(db->GetMinimalPersistentLSN(), 2);
	EXPECT_FALSE(db->Lookup("testidx",DataHolder("Key1")).isEmpty());
	EXPECT_FALSE(db->Lookup("testidx",DataHolder("Key2")).isEmpty());
	EXPECT_TRUE(db->Lookup("testidx",DataHolder("Key3")).isEmpty());

  StringSetOperation("testidx", 3, "Key3").applyTo(*db);
	EXPECT_FALSE(db->Lookup("testidx",DataHolder("Key1")).isEmpty());
	EXPECT_FALSE(db->Lookup("testidx",DataHolder("Key2")).isEmpty());
	EXPECT_TRUE(db->Lookup("testidx",DataHolder("Key3")).isEmpty());
	EXPECT_TRUE(db->Lookup("testidx",DataHolder("Key4")).isEmpty());
  delete db;
}
