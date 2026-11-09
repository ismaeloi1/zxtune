// Usage: module_test_stream_props <file> <expected properties>...
// Host check: sampled formats describe the stored stream with standard attributes.
// Expected properties are given as name=value pairs, e.g. Codec=FLAC Samplerate=44100 SampleBits=16, value '*' means
// any non-empty value. All the stream attributes found are printed.
#include "binary/container_factories.h"
#include "core/service.h"
#include "module/attributes.h"
#include "module/holder.h"
#include "parameters/container.h"
#include "parameters/template.h"

#include "error.h"

#include <cstdio>
#include <fstream>
#include <iterator>
#include <vector>

namespace
{
  Module::Holder::Ptr Open(const char* path)
  {
    std::ifstream f(path, std::ios::binary);
    std::vector<uint8_t> data((std::istreambuf_iterator<char>(f)), {});
    const auto service = ZXTune::Service::Create(Parameters::Container::Create());
    return service->OpenModule(Binary::CreateContainer(Binary::View(data.data(), data.size())), "",
                               Parameters::Container::Create());
  }

  String GetValue(const Parameters::Accessor& props, StringView name)
  {
    Parameters::IntType intVal = 0;
    if (Parameters::FindValue(props, name, intVal))
    {
      return std::to_string(intVal);
    }
    Parameters::StringType strVal;
    if (Parameters::FindValue(props, name, strVal))
    {
      return strVal;
    }
    return {};
  }
}  // namespace

int main(int argc, char** argv)
{
  if (argc < 2)
  {
    std::printf("Usage: %s <file> [name=value]...\n", argv[0]);
    return 1;
  }
  try
  {
    const auto holder = Open(argv[1]);
    const auto props = holder->GetModuleProperties();
    std::printf("%s:", argv[1]);
    for (const auto name : {Module::ATTR_TYPE, Module::ATTR_CODEC, Module::ATTR_SAMPLERATE, Module::ATTR_SAMPLE_BITS,
                            Module::ATTR_CHANNELS, Module::ATTR_BITRATE})
    {
      std::printf(" %s=%s", String(name).c_str(), GetValue(*props, name).c_str());
    }
    std::printf("\n");
    int failures = 0;
    for (int arg = 2; arg < argc; ++arg)
    {
      const StringView expectation(argv[arg]);
      const auto eq = expectation.find('=');
      const auto name = expectation.substr(0, eq);
      const auto expected = expectation.substr(eq + 1);
      const auto actual = GetValue(*props, name);
      if (expected == "*"sv ? actual.empty() : actual != expected)
      {
        std::printf("  %s: expected '%s', got '%s'\n", String(name).c_str(), String(expected).c_str(), actual.c_str());
        ++failures;
      }
    }
    return failures != 0;
  }
  catch (const Error& e)
  {
    std::printf("ERROR: %s\n", e.ToString().c_str());
    return 1;
  }
}
