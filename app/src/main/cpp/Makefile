CXXFLAGS := -std=c++11 -Wall -Wextra -pedantic-errors -O2 $(CXXFLAGS)
LDFLAGS := -Wl,-s $(LDFLAGS)
ifdef MINGW_PREFIX
  LDFLAGS := -municode -static $(LDFLAGS)
  TARGET ?= tsreadex.exe
else
  LDFLAGS := $(LDFLAGS)
  TARGET ?= tsreadex
endif

all: $(TARGET)
$(TARGET): tsreadex.cpp util.cpp util.hpp id3conv.cpp id3conv.hpp servicefilter.cpp servicefilter.hpp aac.cpp aac.hpp huffman.cpp huffman.hpp traceb24.cpp traceb24.hpp
	$(CXX) $(CXXFLAGS) $(CPPFLAGS) $(LDFLAGS) $(TARGET_ARCH) -o $@ tsreadex.cpp util.cpp id3conv.cpp servicefilter.cpp aac.cpp huffman.cpp traceb24.cpp
clean:
	$(RM) $(TARGET)
