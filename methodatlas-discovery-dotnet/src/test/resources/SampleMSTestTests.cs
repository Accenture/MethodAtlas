using System;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace MyCompany.Tests
{
    [TestClass]
    public class SecurityTests
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
