using System;
using Xunit;

namespace MyCompany.Security.Tests
{
    public class LoginTests
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

        public void NotATestMethod()
        {
        }
    }
}
