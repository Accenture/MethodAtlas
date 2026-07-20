// Regression fixture: the framework-identifying `using Xunit;` appears in a
// namespace that the parse tree visits AFTER a method in an earlier namespace.
// Framework detection must therefore be resolved after the whole file is
// walked, not lazily at the first method.
namespace First
{
    public class ATests
    {
        [Xunit.Fact]
        public void FirstTest()
        {
        }
    }
}

namespace Second
{
    using Xunit;

    public class BTests
    {
        [Fact(DisplayName = "Nice name")]
        public void SecondTest()
        {
        }
    }
}
