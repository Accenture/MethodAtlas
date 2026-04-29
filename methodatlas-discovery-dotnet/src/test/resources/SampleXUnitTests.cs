using System;
using Xunit;

namespace MyCompany.Tests
{
    public class SecurityTests
    {
        [Fact]
        [Trait("Tag", "security")]
        public void TestLogin()
        {
        }

        [Theory]
        [InlineData("admin")]
        public void TestWithParam(string role)
        {
        }

        [Fact(DisplayName = "Login with display name")]
        public void TestLoginWithName()
        {
        }
    }
}
