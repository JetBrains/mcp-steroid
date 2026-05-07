// Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license.
#include <iostream>
#include <string>

std::string greet(const std::string& name) {
    return "Hello, " + name;
}

int main() {
    std::cout << greet("CLion") << std::endl;
    return 0;
}
