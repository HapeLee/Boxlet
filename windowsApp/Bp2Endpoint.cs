namespace Boxlet.Windows;

public static class Bp2Endpoint
{
    public static (string Host, int Port) Parse(string value)
    {
        var address = value.Trim();
        // Uri normalizes a missing path to '/', and can discard a trailing ':'.
        // Validate those parts before parsing so UI input remains an endpoint only.
        if (address.Length == 0 || address.Any(char.IsWhiteSpace) || address.EndsWith(':') ||
            address.IndexOfAny(['/', '\\', '?', '#', '@']) >= 0 ||
            !Uri.TryCreate("bp2://" + address, UriKind.Absolute, out var uri) ||
            string.IsNullOrEmpty(uri.Host) || uri.UserInfo.Length > 0 ||
            uri.AbsolutePath is not ("" or "/") || uri.Query.Length > 0 || uri.Fragment.Length > 0 ||
            (uri.Port != -1 && (uri.Port < 1 || uri.Port > 65535)))
            throw new InvalidOperationException("请输入 IP 或 主机:端口；IPv6 请使用 [地址]:端口");
        return (uri.Host.Trim('[', ']'), uri.Port == -1 ? 53318 : uri.Port);
    }
}
