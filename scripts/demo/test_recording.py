import unittest

from read_only_bridge import allowed_call
from record_cli import successful


class RecordingGuardTest(unittest.TestCase):
    interface = "com.example.TransportRpcService"

    def call(self, **changes):
        args = dict(env="test", interfaceName=self.interface, method="getLoadingTypeDict", args=[], paramTypes=[])
        args.update(changes)
        return {"name": "dubbo_invoke", "arguments": args}

    def test_only_selected_no_arg_test_dictionary_is_allowed(self):
        self.assertTrue(allowed_call(self.call(), self.interface))
        for changes in [{"env": "prod"}, {"method": "deleteOrder"}, {"args": [1]},
                        {"paramTypes": ["java.lang.String"]}, {"interfaceName": "other"},
                        {"attachments": {"userId": "1"}}]:
            with self.subTest(changes=changes):
                self.assertFalse(allowed_call(self.call(**changes), self.interface))

    def test_environment_switch_and_missing_scope_are_denied(self):
        for params in [None, "bad", {}, {"name": "dubbo_switch_env", "arguments": {"env": "test"}},
                       {"name": "dubbo_invoke", "arguments": {"method": "getLoadingTypeDict"}}]:
            self.assertFalse(allowed_call(params, self.interface))

    def test_read_only_discovery_requires_test_environment(self):
        for tool in ["dubbo_list_services", "dubbo_get_service_detail"]:
            args = {"env": "test"}
            if tool == "dubbo_get_service_detail":
                args["interfaceName"] = self.interface
            self.assertTrue(allowed_call({"name": tool, "arguments": args}, self.interface))
            args["env"] = "prod"
            self.assertFalse(allowed_call({"name": tool, "arguments": args}, self.interface))


class CaptureVerificationTest(unittest.TestCase):
    def events(self):
        import json
        result = {"status": "SUCCESS", "data": {"success": True, "data": [{"code": 0, "desc": "example"}]}}
        return [{"type": "item.completed", "item": {
            "type": "mcp_tool_call", "tool": name, "status": "completed", "error": None,
            "result": {"content": [{"type": "text", "text": json.dumps(result)}]}
        }} for name in ["dubbo_list_services", "dubbo_get_service_detail", "dubbo_invoke"]]

    def test_success_requires_all_three_real_completed_calls(self):
        self.assertTrue(successful(self.events()))
        self.assertFalse(successful(self.events()[:-1]))
        self.assertFalse(successful(list(reversed(self.events()))))

    def test_zero_exit_with_approval_failure_is_not_success(self):
        events = self.events()
        events[0]["item"].update(status="failed", result=None, error={"message": "requires approval"})
        self.assertFalse(successful(events))

    def test_usage_limit_or_error_result_is_not_success(self):
        self.assertFalse(successful(self.events() + [{"type": "turn.failed"}]))
        events = self.events()
        events[-1]["item"]["result"]["isError"] = True
        self.assertFalse(successful(events))


if __name__ == "__main__":
    unittest.main()
