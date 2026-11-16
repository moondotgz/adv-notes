// Host-side smoke test for casio_api (not part of the app build).
#include "casio_api.h"
#include <cstdio>
int main() {
    casio::Options o;
    const char *exprs[] = {"√(8)+1÷3", "(1+√(5))÷2", "3√(2)÷4", "1/3+1/4", "√(8)", "sin(30)", "2π", "∫(x², 0, 3)", "d/dx(x³, 2)", "Σ(x, 1, 100)", "5→A", "A²+1",
                           "Pol(1, 1)", "1÷0", "12×7", "3+", "6.02ᴇ23×2"};
    for (auto e : exprs) std::printf("%-16s %s\n", e, casio::evaluate(e, o).c_str());
    o.exact = false;
    std::printf("%-16s %s\n", "1/3 (dec)", casio::evaluate("1/3", o).c_str());
    o.exact = true; o.complex = true;
    std::printf("%-16s %s\n", "(1+2i)×(3−i)", casio::evaluate("(1+2i)×(3−i)", o).c_str());
    o.complex = false; o.base = 16;
    std::printf("%-16s %s\n", "FF+1 (hex)", casio::evaluate("FF+1", o).c_str());
    o.base = 0;
    std::printf("poly x²−5x+6   %s\n", casio::polynomial({1, -5, 6}, o).c_str());
    std::printf("poly x²+1      %s\n", casio::polynomial({1, 0, 1}, o).c_str());
    std::printf("lin 2x2        %s\n", casio::linearSystem(2, {1, 1, 1, -1}, {10, 2}, o).c_str());
    std::printf("stats 1var     %s\n", casio::statistics(0, {1, 2, 3, 4}, {}, o).c_str());
    std::printf("stats a+bx     %s\n", casio::statistics(1, {1, 2, 3, 4}, {3, 5, 7, 9}, o).c_str());
    std::printf("vars           %s\n", casio::variables(o).c_str());
    std::printf("consts         %.200s\n", casio::constants().c_str());
    std::printf("convs          %.200s\n", casio::conversions().c_str());
    std::printf("%s\n", casio::evaluate("2‹c0›", o).c_str());
    std::printf("%s\n", casio::evaluate("10‹in▶cm›", o).c_str());
}
