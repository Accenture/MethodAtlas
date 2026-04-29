using System;
using NUnit.Framework;

namespace MyCompany.Security.Tests
{
    [TestFixture]
    public class LoginTests
    {
        [Test]
        [Category("authentication")]
        public void TestLogin()
        {
            Assert.Pass();
        }

        [Test]
        public void TestLogout()
        {
        }

        [TestCase("admin")]
        [TestCase("user")]
        public void TestPermissions(string role)
        {
        }

        public void NotATestMethod()
        {
        }
    }
}
