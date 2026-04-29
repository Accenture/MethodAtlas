using System;
using NUnit.Framework;

namespace MyCompany.Tests
{
    [TestFixture]
    public class SecurityTests
    {
        [Test]
        [Category("authentication")]
        public void TestLogin()
        {
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

        public void NotATest()
        {
        }
    }
}
