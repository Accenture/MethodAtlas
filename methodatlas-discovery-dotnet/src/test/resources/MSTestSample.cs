using System;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace MyCompany.Security.Tests
{
    [TestClass]
    public class LoginTests
    {
        [TestMethod]
        [TestCategory("security")]
        public void TestLogin()
        {
        }

        [DataTestMethod]
        [DataRow("admin")]
        public void TestWithParam(string role)
        {
        }
    }
}
